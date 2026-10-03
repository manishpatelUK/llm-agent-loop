package io.github.manishpateluk.llmagentloop.tool.api;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An API the agent may call through {@code api_request}, registered by the implementor. The
 * model only ever names a connection and a path under its {@link #baseUrl()}: it can't reach
 * other hosts, and it never sees the credentials ({@link ApiAuth}).
 *
 * <pre>{@code
 * ApiConnection stripe = ApiConnection.builder("stripe", "https://api.stripe.com/v1")
 *         .description("Payments: customers, invoices, subscriptions")
 *         .auth(ApiAuth.bearer(scope -> secrets.stripeKeyFor(scope.tenantId())))
 *         .allowWrites()                                   // read-only (GET/HEAD) unless enabled
 *         .allowedPaths("/customers/**", "/invoices/**")   // everything under the base URL if not set
 *         .build();
 * }</pre>
 */
public final class ApiConnection {

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    static final Set<String> READ_METHODS = Set.of("GET", "HEAD");
    static final Set<String> ALL_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

    private final String name;
    private final URI baseUrl;
    private final String description;
    private final ApiAuth auth;
    private final Set<String> allowedMethods;
    private final List<String> allowedPaths;
    private final List<Pattern> allowedPathPatterns;
    private final Map<String, String> defaultHeaders;
    private final Duration timeout;
    private final int maxResponseChars;

    private ApiConnection(Builder b) {
        this.name = b.name;
        this.baseUrl = b.baseUrl;
        this.description = b.description;
        this.auth = b.auth;
        this.allowedMethods = Set.copyOf(b.allowedMethods);
        this.allowedPaths = List.copyOf(b.allowedPaths);
        this.allowedPathPatterns = allowedPaths.stream().map(ApiConnection::glob).toList();
        this.defaultHeaders = Map.copyOf(b.defaultHeaders);
        this.timeout = b.timeout;
        this.maxResponseChars = b.maxResponseChars;
    }

    public static Builder builder(String name, String baseUrl) {
        return new Builder(name, baseUrl);
    }

    public String name() {
        return name;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public String description() {
        return description;
    }

    public ApiAuth auth() {
        return auth;
    }

    public Set<String> allowedMethods() {
        return allowedMethods;
    }

    public List<String> allowedPaths() {
        return allowedPaths;
    }

    public Map<String, String> defaultHeaders() {
        return defaultHeaders;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxResponseChars() {
        return maxResponseChars;
    }

    /** {@code path} is relative to the base URL's path, e.g. {@code /customers/cus_123}. */
    boolean pathAllowed(String path) {
        return allowedPathPatterns.stream().anyMatch(p -> p.matcher(path).matches());
    }

    /**
     * {@code **} matches anything including {@code /}; {@code *} matches within one segment. A
     * trailing {@code /**} also matches the bare prefix, so {@code /customers/**} covers
     * {@code /customers} itself — usually the list endpoint.
     */
    private static Pattern glob(String glob) {
        if (glob.endsWith("/**")) {
            return Pattern.compile(glob(glob.substring(0, glob.length() - 3)).pattern() + "(/.*)?");
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    public static final class Builder {

        private final String name;
        private final URI baseUrl;
        private String description = "";
        private ApiAuth auth = ApiAuth.NONE;
        private final Set<String> allowedMethods = new LinkedHashSet<>(READ_METHODS);
        private final List<String> allowedPaths = new ArrayList<>();
        private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
        private Duration timeout = Duration.ofSeconds(30);
        private int maxResponseChars = 20_000;

        private Builder(String name, String baseUrl) {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Connection name must match " + NAME + ": " + name);
            }
            this.name = name;
            Objects.requireNonNull(baseUrl, "baseUrl");
            URI uri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                throw new IllegalArgumentException("baseUrl must be http(s): " + baseUrl);
            }
            if (uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("baseUrl must have a host and no query, fragment or credentials: " + baseUrl);
            }
            this.baseUrl = uri;
        }

        /** What this API is for — shown to the model so it knows when to use it. */
        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        public Builder auth(ApiAuth auth) {
            this.auth = Objects.requireNonNull(auth, "auth");
            return this;
        }

        /** Also allow POST, PUT, PATCH and DELETE. Connections are read-only (GET, HEAD) by default. */
        public Builder allowWrites() {
            allowedMethods.addAll(ALL_METHODS);
            return this;
        }

        /** Exactly these methods, replacing the read-only default. */
        public Builder allowedMethods(String... methods) {
            allowedMethods.clear();
            for (String method : methods) {
                String m = method.toUpperCase(Locale.ROOT);
                if (!ALL_METHODS.contains(m)) {
                    throw new IllegalArgumentException("Unsupported method: " + method);
                }
                allowedMethods.add(m);
            }
            return this;
        }

        /**
         * Path patterns under the base URL the agent may call, e.g. {@code "/customers/**"}
         * ({@code **} spans segments, {@code *} matches within one). Defaults to everything.
         */
        public Builder allowedPaths(String... patterns) {
            for (String pattern : patterns) {
                if (!pattern.startsWith("/")) {
                    throw new IllegalArgumentException("Path patterns must start with '/': " + pattern);
                }
                allowedPaths.add(pattern);
            }
            return this;
        }

        /** Sent on every request, e.g. an API version header. Overridden by {@link ApiAuth} headers of the same name. */
        public Builder defaultHeader(String name, String value) {
            defaultHeaders.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Response bodies longer than this are cut off before reaching the model. */
        public Builder maxResponseChars(int maxResponseChars) {
            if (maxResponseChars <= 0) {
                throw new IllegalArgumentException("maxResponseChars must be positive");
            }
            this.maxResponseChars = maxResponseChars;
            return this;
        }

        public ApiConnection build() {
            if (allowedMethods.isEmpty()) {
                throw new IllegalStateException("A connection needs at least one allowed method");
            }
            if (allowedPaths.isEmpty()) {
                allowedPaths.add("/**");
            }
            return new ApiConnection(this);
        }
    }
}
