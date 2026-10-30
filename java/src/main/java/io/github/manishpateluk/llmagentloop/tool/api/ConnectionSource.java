package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@link ApiConnection}s a run may use, looked up from its {@link Scope} on every call — so
 * each tenant (or user) can have its own connections, added or removed while agents keep running.
 * One agent, built once, then serves every tenant:
 *
 * <pre>{@code
 * ConnectionSource connections = scope -> connectionsDao.forTenant(scope.tenantId()).stream()
 *         .map(row -> ApiConnection.builder(row.name(), row.baseUrl())
 *                 .description(row.description())
 *                 .auth(ApiAuth.bearer(s -> secrets.get(s.tenantId(), row.secretRef())))
 *                 .build())
 *         .toList();
 * registry.registerAll(ApiTools.all(connections));   // api_connections + api_request
 * }</pre>
 *
 * <p>Called on the run's thread for every API tool call, so blocking I/O is fine; cache in your
 * implementation if the lookup is slow. Names must be unique within one scope's list.
 */
@FunctionalInterface
public interface ConnectionSource {

    /** The connections {@code scope}'s runs may use; empty if none. */
    List<ApiConnection> connections(Scope scope);

    /** The same connections for every run. */
    static ConnectionSource of(List<ApiConnection> connections) {
        Objects.requireNonNull(connections, "connections");
        List<ApiConnection> fixed = List.copyOf(connections);
        byName(fixed, IllegalArgumentException::new);
        return scope -> fixed;
    }

    /** {@code scope}'s connection called {@code name}, if it has one. */
    default Optional<ApiConnection> find(Scope scope, String name) {
        return Optional.ofNullable(byName(scope).get(name));
    }

    /** {@code scope}'s connections by name, in order. */
    default Map<String, ApiConnection> byName(Scope scope) {
        List<ApiConnection> connections = connections(scope);
        return byName(connections == null ? List.of() : connections, IllegalStateException::new);
    }

    private static Map<String, ApiConnection> byName(
            List<ApiConnection> connections, java.util.function.Function<String, RuntimeException> duplicate) {
        Map<String, ApiConnection> byName = new LinkedHashMap<>();
        for (ApiConnection connection : connections) {
            if (byName.put(connection.name(), connection) != null) {
                throw duplicate.apply("Duplicate connection name: " + connection.name());
            }
        }
        return byName;
    }
}
