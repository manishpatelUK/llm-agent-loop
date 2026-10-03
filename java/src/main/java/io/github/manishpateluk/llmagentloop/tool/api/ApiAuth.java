package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * How an {@link ApiConnection} authenticates, resolved per run from its {@link Scope} — so each
 * tenant (or user) can use its own credentials. Credentials are added to the outgoing request by
 * the tool; they never appear in the model's prompt, history, or tool results.
 */
public interface ApiAuth {

    /** No authentication. */
    ApiAuth NONE = scope -> Map.of();

    /** Headers to add to every request made for {@code scope}. */
    Map<String, String> headers(Scope scope);

    /** Query parameters to add to every request made for {@code scope}, for APIs that take a key that way. */
    default Map<String, String> queryParameters(Scope scope) {
        return Map.of();
    }

    /** {@code Authorization: Bearer <token>}. */
    static ApiAuth bearer(Function<Scope, String> token) {
        Objects.requireNonNull(token, "token");
        return scope -> Map.of("Authorization", "Bearer " + token.apply(scope));
    }

    /** A custom header, e.g. {@code header("X-Api-Key", scope -> keys.forTenant(scope.tenantId()))}. */
    static ApiAuth header(String name, Function<Scope, String> value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return scope -> Map.of(name, value.apply(scope));
    }

    /** HTTP Basic authentication. */
    static ApiAuth basic(Function<Scope, String> username, Function<Scope, String> password) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        return scope -> Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (username.apply(scope) + ":" + password.apply(scope)).getBytes(StandardCharsets.UTF_8)));
    }

    /** A key passed as a query parameter, e.g. {@code ?api_key=...}. */
    static ApiAuth queryParameter(String name, Function<Scope, String> value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return new ApiAuth() {
            @Override
            public Map<String, String> headers(Scope scope) {
                return Map.of();
            }

            @Override
            public Map<String, String> queryParameters(Scope scope) {
                return Map.of(name, value.apply(scope));
            }
        };
    }
}
