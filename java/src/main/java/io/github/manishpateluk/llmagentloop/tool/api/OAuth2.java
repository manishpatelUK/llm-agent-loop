package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.ScopeLevel;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * {@link ApiAuth} for OAuth2 APIs: fetches an access token from the token endpoint, caches it per
 * scope, and fetches a new one shortly before it expires or when the API answers 401.
 *
 * <pre>{@code
 * // Machine to machine: the client credentials grant
 * ApiAuth billing = OAuth2.clientCredentials("https://auth.example.com/oauth/token",
 *                 scope -> secrets.clientId(scope.tenantId()), scope -> secrets.clientSecret(scope.tenantId()))
 *         .scopes("invoices.read", "invoices.write")
 *         .build();
 *
 * // On a tenant's behalf: a refresh token your app got when the tenant connected the API
 * ApiAuth crm = OAuth2.refreshToken("https://crm.example.com/oauth/token",
 *                 scope -> crmClientId, scope -> crmClientSecret,
 *                 OAuth2.RefreshTokens.of(scope -> tokens.load(scope.tenantId()),
 *                         (scope, rotated) -> tokens.store(scope.tenantId(), rotated)))
 *         .build();
 * }</pre>
 *
 * <p>Tokens are cached per {@code scope.atLevel(cacheLevel)} — the tenant by default — and the
 * credential functions and {@link RefreshTokens} are called with that same reduced scope, so a
 * cached token is only ever used for the scope whose credentials fetched it. Use
 * {@link Builder#cacheLevel} with {@link ScopeLevel#USER} when each user connects their own
 * account. Credentials and tokens come only from your functions, never from the model, and are
 * kept out of exception messages: a failed token request reports its status and the provider's
 * {@code error} code and description.
 *
 * <p>The authorization-code flow itself (sending the user to the provider and back) belongs in
 * your app; hand the refresh token it produces to {@link #refreshToken}.
 */
public final class OAuth2 implements ApiAuth {

    /** Where the refresh token for a scope is kept: your database or secret store. */
    public interface RefreshTokens {

        /** The current refresh token for {@code scope}. */
        String current(Scope scope);

        /** The provider issued a new refresh token (rotation): store it, as the old one may stop working. */
        default void replaced(Scope scope, String refreshToken) {
        }

        static RefreshTokens of(Function<Scope, String> current, BiConsumer<Scope, String> replaced) {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(replaced, "replaced");
            return new RefreshTokens() {
                @Override
                public String current(Scope scope) {
                    return current.apply(scope);
                }

                @Override
                public void replaced(Scope scope, String refreshToken) {
                    replaced.accept(scope, refreshToken);
                }
            };
        }
    }

    /** How the client authenticates to the token endpoint. */
    public enum ClientAuthentication {
        /** {@code client_id} and {@code client_secret} in the form body ({@code client_secret_post}); the default. */
        POST,
        /** HTTP Basic with the client id and secret ({@code client_secret_basic}). */
        BASIC
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Assumed lifetime when a token response has no {@code expires_in}. */
    private static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);

    private record Token(String value, Instant expiresAt) {
    }

    private final URI tokenUrl;
    private final Function<Scope, String> clientId;
    private final Function<Scope, String> clientSecret;
    private final RefreshTokens refreshTokens;
    private final List<String> scopes;
    private final ClientAuthentication clientAuthentication;
    private final ScopeLevel cacheLevel;
    private final Duration refreshBefore;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient client;
    private final Map<Scope, Token> tokens = new ConcurrentHashMap<>();
    private final Map<Scope, ReentrantLock> locks = new ConcurrentHashMap<>();

    private OAuth2(Builder b) {
        this.tokenUrl = b.tokenUrl;
        this.clientId = b.clientId;
        this.clientSecret = b.clientSecret;
        this.refreshTokens = b.refreshTokens;
        this.scopes = List.copyOf(b.scopes);
        this.clientAuthentication = b.clientAuthentication;
        this.cacheLevel = b.cacheLevel;
        this.refreshBefore = b.refreshBefore;
        this.timeout = b.timeout;
        this.clock = b.clock;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(b.timeout).build();
    }

    /** The client credentials grant: the app authenticates as itself. */
    public static Builder clientCredentials(String tokenUrl, Function<Scope, String> clientId, Function<Scope, String> clientSecret) {
        return new Builder(tokenUrl, clientId, clientSecret, null);
    }

    /**
     * The refresh token grant: access on a tenant's or user's behalf, from a refresh token your app
     * obtained (e.g. through the authorization-code flow). {@code clientSecret} may return
     * {@code null} for public clients.
     */
    public static Builder refreshToken(String tokenUrl, Function<Scope, String> clientId, Function<Scope, String> clientSecret,
                                       RefreshTokens refreshTokens) {
        return new Builder(tokenUrl, clientId, clientSecret, Objects.requireNonNull(refreshTokens, "refreshTokens"));
    }

    @Override
    public Map<String, String> headers(Scope scope) {
        return Map.of("Authorization", "Bearer " + accessToken(scope));
    }

    /** Drops the cached token, so the retried request fetches a new one. */
    @Override
    public boolean renewAfterUnauthorized(Scope scope) {
        invalidate(scope);
        return true;
    }

    /** Forgets {@code scope}'s cached access token, e.g. after the account is reconnected. */
    public void invalidate(Scope scope) {
        tokens.remove(scope.atLevel(cacheLevel));
    }

    /** {@code scope}'s access token: the cached one while it has more than {@code refreshBefore} left, otherwise a new one. */
    public String accessToken(Scope scope) {
        Scope key = scope.atLevel(cacheLevel);
        Token cached = tokens.get(key);
        if (fresh(cached)) {
            return cached.value();
        }
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            cached = tokens.get(key);
            if (fresh(cached)) {
                return cached.value();
            }
            Token token = fetch(key);
            tokens.put(key, token);
            return token.value();
        } finally {
            lock.unlock();
        }
    }

    private boolean fresh(Token token) {
        return token != null && clock.instant().plus(refreshBefore).isBefore(token.expiresAt());
    }

    private Token fetch(Scope scope) {
        Map<String, String> form = new LinkedHashMap<>();
        if (refreshTokens == null) {
            form.put("grant_type", "client_credentials");
        } else {
            String refreshToken = refreshTokens.current(scope);
            if (refreshToken == null || refreshToken.isBlank()) {
                throw new IllegalStateException("No OAuth2 refresh token is stored for " + scope.key()
                        + "; the account needs to be connected (again)");
            }
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", refreshToken);
        }
        if (!scopes.isEmpty()) {
            form.put("scope", String.join(" ", scopes));
        }
        String id = Objects.requireNonNull(clientId.apply(scope), "clientId returned null");
        String secret = clientSecret.apply(scope);
        HttpRequest.Builder request = HttpRequest.newBuilder(tokenUrl)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", ApiBodyType.FORM.mediaType());
        if (clientAuthentication == ClientAuthentication.BASIC) {
            // RFC 6749 section 2.3.1: the id and secret are form-encoded before Basic encoding.
            String credentials = encode(id) + ":" + (secret == null ? "" : encode(secret));
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        } else {
            form.put("client_id", id);
            if (secret != null) {
                form.put("client_secret", secret);
            }
        }
        request.POST(HttpRequest.BodyPublishers.ofString(ApiHttp.formEncode(new ArrayList<>(form.entrySet()))));

        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("OAuth2 token request to " + where() + " failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OAuth2 token request to " + where() + " was interrupted", e);
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (JacksonException e) {
            body = JSON.createObjectNode();
        }
        if (response.statusCode() / 100 != 2 || !body.path("access_token").isString()) {
            StringBuilder message = new StringBuilder("OAuth2 token request to ").append(where())
                    .append(" failed: HTTP ").append(response.statusCode());
            String error = body.path("error").asString("");
            if (!error.isBlank()) {
                message.append(' ').append(cap(error, 64));
            }
            String description = body.path("error_description").asString("");
            if (!description.isBlank()) {
                message.append(": ").append(cap(description, 200));
            }
            if (response.statusCode() / 100 == 2) {
                message.append(" (no access_token in the response)");
            }
            throw new IllegalStateException(message.toString());
        }
        if (refreshTokens != null) {
            String rotated = body.path("refresh_token").asString("");
            if (!rotated.isBlank()) {
                refreshTokens.replaced(scope, rotated);
            }
        }
        long expiresIn = body.path("expires_in").asLong(0);
        Duration lifetime = expiresIn > 0 ? Duration.ofSeconds(expiresIn) : DEFAULT_LIFETIME;
        return new Token(body.path("access_token").asString(), clock.instant().plus(lifetime));
    }

    /** The token endpoint without its query, for messages. */
    private String where() {
        return tokenUrl.getScheme() + "://" + tokenUrl.getAuthority() + (tokenUrl.getPath() == null ? "" : tokenUrl.getPath());
    }

    private static String cap(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static final class Builder {

        private final URI tokenUrl;
        private final Function<Scope, String> clientId;
        private final Function<Scope, String> clientSecret;
        private final RefreshTokens refreshTokens;
        private final List<String> scopes = new ArrayList<>();
        private ClientAuthentication clientAuthentication = ClientAuthentication.POST;
        private ScopeLevel cacheLevel = ScopeLevel.TENANT;
        private Duration refreshBefore = Duration.ofSeconds(60);
        private Duration timeout = Duration.ofSeconds(30);
        private Clock clock = Clock.systemUTC();

        private Builder(String tokenUrl, Function<Scope, String> clientId, Function<Scope, String> clientSecret,
                        RefreshTokens refreshTokens) {
            Objects.requireNonNull(tokenUrl, "tokenUrl");
            URI uri = URI.create(tokenUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("tokenUrl must be an http(s) URL without credentials: " + tokenUrl);
            }
            this.tokenUrl = uri;
            this.clientId = Objects.requireNonNull(clientId, "clientId");
            this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
            this.refreshTokens = refreshTokens;
        }

        /** OAuth2 scopes to ask for, sent space-separated. */
        public Builder scopes(String... scopes) {
            this.scopes.addAll(List.of(scopes));
            return this;
        }

        public Builder clientAuthentication(ClientAuthentication clientAuthentication) {
            this.clientAuthentication = Objects.requireNonNull(clientAuthentication, "clientAuthentication");
            return this;
        }

        /** Which part of the scope a token belongs to; {@link ScopeLevel#TENANT} by default. */
        public Builder cacheLevel(ScopeLevel cacheLevel) {
            this.cacheLevel = Objects.requireNonNull(cacheLevel, "cacheLevel");
            return this;
        }

        /** Fetch a new token once the cached one has less than this left; 60 seconds by default. */
        public Builder refreshBefore(Duration refreshBefore) {
            if (refreshBefore == null || refreshBefore.isNegative()) {
                throw new IllegalArgumentException("refreshBefore must be zero or positive");
            }
            this.refreshBefore = refreshBefore;
            return this;
        }

        /** Time limit for each token request; 30 seconds by default. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** The clock that expiry is judged by; for tests. */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public OAuth2 build() {
            return new OAuth2(this);
        }
    }
}
