package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionSourceTest {

    private final ApiStub api = new ApiStub().on("GET /v1/contacts", 200, "{\"data\":[]}");
    private final Map<String, List<ApiConnection>> byTenant = new ConcurrentHashMap<>();
    private final ConnectionSource source = scope -> byTenant.getOrDefault(scope.tenantId(), List.of());
    private final ToolContext acme = ApiStub.context("acme");
    private final ToolContext globex = ApiStub.context("globex");

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void eachTenantSeesAndUsesOnlyItsOwnConnections() {
        byTenant.put("acme", new CopyOnWriteArrayList<>(List.of(ApiConnection.builder("crm", api.url("/v1"))
                .description("Acme's CRM").auth(ApiAuth.bearer(scope -> "key-" + scope.tenantId())).build())));

        assertThat(list(acme)).contains("- crm: Acme's CRM (methods: GET, HEAD; paths: /**)");
        assertThat(list(globex)).isEqualTo("No API connections are set up for this user.");

        assertThat(request(acme, Map.of("connection", "crm", "method", "GET", "path", "/contacts"))).startsWith("HTTP 200");
        assertThat(api.seen.getFirst().auth()).isEqualTo("Bearer key-acme");
        assertThatThrownBy(() -> request(globex, Map.of("connection", "crm", "method", "GET", "path", "/contacts")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("No connection called 'crm'; available: []");
        assertThat(api.seen).hasSize(1);
    }

    @Test
    void connectionsAddedAtRuntimeArePickedUpByTheSameTools() {
        RegisteredTool connections = ApiTools.connections(source);
        assertThat(connections.handler().handle(new HashMap<>(), acme)).startsWith("No API connections");

        byTenant.put("acme", List.of(ApiConnection.builder("billing", api.url("/v1")).bodyType(ApiBodyType.FORM).build()));

        assertThat(connections.handler().handle(new HashMap<>(), acme))
                .contains("- billing (methods: GET, HEAD; paths: /**; bodies: application/x-www-form-urlencoded)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theToolDefinitionsAreTheSameForEveryTenantSoTheyStayCacheable() {
        RegisteredTool request = ApiTools.request(source);
        Map<?, ?> properties = (Map<?, ?>) request.definition().getParameters().get("properties");

        assertThat(((Map<?, ?>) properties.get("connection")).containsKey("enum")).isFalse();
        assertThat((List<Object>) ((Map<?, ?>) properties.get("method")).get("enum"))
                .containsExactly("DELETE", "GET", "HEAD", "PATCH", "POST", "PUT");
        assertThat(request.definition().getDescription()).contains("Call api_connections first");
        assertThat(ApiTools.all(source)).extracting(t -> t.definition().getName())
                .containsExactly("api_connections", "api_request", "api_describe");
    }

    @Test
    void apiRequestDeclaresWhichCallsWrite() {
        RegisteredTool request = ApiTools.request(source);

        assertThat(request.writes(Map.of("method", "post"), acme)).isTrue();
        assertThat(request.writes(Map.of("method", "DELETE"), acme)).isTrue();
        assertThat(request.writes(Map.of("method", "GET"), acme)).isFalse();
        assertThat(request.writes(Map.of("method", "HEAD"), acme)).isFalse();
    }

    @Test
    void duplicateNamesAreRefused() {
        ApiConnection crm = ApiConnection.builder("crm", api.url("/v1")).build();
        assertThatThrownBy(() -> ConnectionSource.of(List.of(crm, crm))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate connection name: crm");

        byTenant.put("acme", List.of(crm, crm));
        assertThatThrownBy(() -> list(acme)).isInstanceOf(IllegalStateException.class);
    }

    private String list(ToolContext context) {
        return ApiTools.connections(source).handler().handle(new HashMap<>(), context);
    }

    private String request(ToolContext context, Map<String, Object> args) {
        return ApiTools.request(source).handler().handle(new HashMap<>(args), context);
    }
}
