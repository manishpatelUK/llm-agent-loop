package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.ScopeLevel;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2Test {

    private final ApiStub api = new ApiStub();
    private final AtomicInteger issued = new AtomicInteger();
    /** The access token the API currently accepts. */
    private final AtomicReference<String> accepted = new AtomicReference<>("");
    private final ToolContext acme = ApiStub.context("acme");

    OAuth2Test() {
        api.on("POST /oauth/token", seen -> {
            String token = "tok-" + issued.incrementAndGet();
            accepted.set(token);
            return ApiStub.Reply.json(200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600"
                    + (seen.body().contains("refresh_token") ? ",\"refresh_token\":\"refresh-" + issued.get() + "\"" : "") + "}");
        });
        api.on("GET /v1/invoices", seen -> ("Bearer " + accepted.get()).equals(seen.auth())
                ? ApiStub.Reply.json(200, "{\"data\":[]}")
                : ApiStub.Reply.json(401, "{\"error\":\"invalid_token\"}"));
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void clientCredentialsTokensAreFetchedOnceAndCachedPerTenant() {
        OAuth2 auth = OAuth2.clientCredentials(api.url("/oauth/token"), scope -> "id-" + scope.tenantId(), scope -> "s3cret")
                .scopes("invoices.read", "invoices.write").build();

        assertThat(get(auth, acme)).startsWith("HTTP 200");
        assertThat(get(auth, ApiStub.context("acme", "bob"))).startsWith("HTTP 200");   // same tenant: cached token

        List<ApiStub.Seen> tokenRequests = tokenRequests();
        assertThat(tokenRequests).hasSize(1);
        assertThat(tokenRequests.getFirst().body())
                .isEqualTo("grant_type=client_credentials&scope=invoices.read+invoices.write&client_id=id-acme&client_secret=s3cret");
        assertThat(api.seen).filteredOn(s -> s.path().equals("/v1/invoices")).extracting(ApiStub.Seen::auth)
                .containsOnly("Bearer tok-1");

        get(auth, ApiStub.context("globex"));
        assertThat(tokenRequests()).hasSize(2);
        assertThat(tokenRequests().getLast().body()).contains("client_id=id-globex");
    }

    @Test
    void aTokenIsRefreshedAndTheRequestRetriedOnceAfterA401() {
        OAuth2 auth = OAuth2.clientCredentials(api.url("/oauth/token"), scope -> "id", scope -> "secret").build();
        get(auth, acme);
        accepted.set("revoked");   // the API no longer accepts tok-1, though it hasn't expired

        String result = get(auth, acme);

        assertThat(result).startsWith("HTTP 200");
        assertThat(api.seen).filteredOn(s -> s.path().equals("/v1/invoices")).extracting(ApiStub.Seen::auth)
                .containsExactly("Bearer tok-1", "Bearer tok-1", "Bearer tok-2");
        assertThat(tokenRequests()).hasSize(2);
    }

    @Test
    void aTokenIsRefreshedShortlyBeforeItExpires() {
        MutableClock clock = new MutableClock();
        OAuth2 auth = OAuth2.clientCredentials(api.url("/oauth/token"), scope -> "id", scope -> "secret")
                .refreshBefore(Duration.ofSeconds(60)).clock(clock).build();

        assertThat(auth.accessToken(acme.scope())).isEqualTo("tok-1");
        clock.advance(Duration.ofSeconds(3500));
        assertThat(auth.accessToken(acme.scope())).isEqualTo("tok-1");
        clock.advance(Duration.ofSeconds(50));   // 50s left: inside the refresh window
        assertThat(auth.accessToken(acme.scope())).isEqualTo("tok-2");
    }

    @Test
    void theRefreshTokenGrantUsesTheStoredTokenAndSavesARotatedOne() {
        Map<Scope, String> stored = new ConcurrentHashMap<>(Map.of(new Scope("acme", "alice", null), "refresh-0"));
        OAuth2 auth = OAuth2.refreshToken(api.url("/oauth/token"), scope -> "id", scope -> null,
                        OAuth2.RefreshTokens.of(stored::get, stored::put))
                .cacheLevel(ScopeLevel.USER).build();

        assertThat(get(auth, ApiStub.context("acme", "alice"))).startsWith("HTTP 200");

        assertThat(tokenRequests().getFirst().body()).isEqualTo("grant_type=refresh_token&refresh_token=refresh-0&client_id=id");
        assertThat(stored).containsEntry(new Scope("acme", "alice", null), "refresh-1");
        assertThatThrownBy(() -> auth.accessToken(Scope.of("acme", "bob", "s1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No OAuth2 refresh token is stored for acme/bob/*");
    }

    @Test
    void basicClientAuthenticationSendsTheCredentialsAsAHeader() {
        OAuth2 auth = OAuth2.clientCredentials(api.url("/oauth/token"), scope -> "my id", scope -> "p:ss")
                .clientAuthentication(OAuth2.ClientAuthentication.BASIC).build();

        auth.accessToken(acme.scope());

        assertThat(tokenRequests().getFirst().auth())
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("my+id:p%3Ass".getBytes()));
        assertThat(tokenRequests().getFirst().body()).isEqualTo("grant_type=client_credentials");
    }

    @Test
    void aFailedTokenRequestReportsTheProvidersErrorButNoSecrets() {
        api.on("POST /oauth/token", 401, "{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed\"}");
        OAuth2 auth = OAuth2.clientCredentials(api.url("/oauth/token?tenant=x"), scope -> "client-123", scope -> "very-secret-value").build();

        assertThatThrownBy(() -> get(auth, acme))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401 invalid_client: Client authentication failed")
                .hasMessageNotContaining("very-secret-value")
                .hasMessageNotContaining("tenant=x");
        assertThat(api.seen).noneMatch(s -> s.path().equals("/v1/invoices"));
    }

    @Test
    void authWithoutRenewalHandsThe401BackWithoutRetrying() {
        ApiConnection connection = ApiConnection.builder("billing", api.url("/v1")).auth(ApiAuth.bearer(scope -> "stale")).build();

        String result = ApiTools.request(List.of(connection)).handler()
                .handle(new HashMap<>(Map.of("connection", "billing", "method", "GET", "path", "/invoices")), acme);

        assertThat(result).startsWith("HTTP 401").contains("invalid_token");
        assertThat(api.seen).hasSize(1);
    }

    private String get(ApiAuth auth, ToolContext context) {
        ApiConnection connection = ApiConnection.builder("billing", api.url("/v1")).auth(auth).build();
        return ApiTools.request(List.of(connection)).handler()
                .handle(new HashMap<>(Map.of("connection", "billing", "method", "GET", "path", "/invoices")), context);
    }

    private List<ApiStub.Seen> tokenRequests() {
        return api.seen.stream().filter(s -> s.path().equals("/oauth/token")).toList();
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-30T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
