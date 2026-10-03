package io.github.manishpateluk.llmagentloop.tool.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiToolsTest {

    private record Seen(String method, String path, String query, String auth, String body) {
    }

    private HttpServer server;
    private String base;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private ToolContext acme;
    private ToolContext globex;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/", exchange -> {
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String path = exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().set("Set-Cookie", "session=secret");
            switch (path) {
                case "/v1/customers" -> respond(exchange, 200, "application/json",
                        "{\"data\":[{\"id\":\"cus_1\",\"name\":\"Ann\"},{\"id\":\"cus_2\",\"name\":\"Bo\"}],\"has_more\":false}");
                case "/v1/customers/missing" -> respond(exchange, 404, "application/json", "{\"error\":\"No such customer\"}");
                case "/v1/big" -> respond(exchange, 200, "text/plain", "x".repeat(100));
                case "/v1/moved" -> {
                    exchange.getResponseHeaders().set("Location", "https://elsewhere.example/");
                    exchange.sendResponseHeaders(301, -1);
                    exchange.close();
                }
                default -> respond(exchange, 200, "application/json", "{\"ok\":true}");
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        acme = context("acme");
        globex = context("globex");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void addsPerTenantCredentialsAndNeverEchoesThem() {
        ApiConnection crm = connection().auth(ApiAuth.bearer(scope -> "token-" + scope.tenantId())).build();

        String acmeResult = call(crm, acme, Map.of("connection", "crm", "method", "GET", "path", "/customers"));
        call(crm, globex, Map.of("connection", "crm", "method", "GET", "path", "/customers"));

        assertThat(seen).extracting(Seen::auth).containsExactly("Bearer token-acme", "Bearer token-globex");
        assertThat(acmeResult).startsWith("HTTP 200 GET /customers").contains("\"id\":\"cus_1\"")
                .doesNotContain("token-acme", "session=secret");
    }

    @Test
    void selectReturnsJustPartOfAJsonResponse() {
        String result = call(connection().build(), acme,
                Map.of("connection", "crm", "method", "GET", "path", "/customers", "select", "/data/1/name"));

        assertThat(result).endsWith("\n\"Bo\"");
    }

    @Test
    void queryParametersAreEncodedAndAuthQueryParametersAdded() {
        ApiConnection crm = connection().auth(ApiAuth.queryParameter("api_key", scope -> "k&1")).build();

        call(crm, acme, Map.of("connection", "crm", "method", "GET", "path", "/search",
                "query", List.of(Map.of("name", "q", "value", "ann & bo"), Map.of("name", "limit", "value", "5"))));

        assertThat(seen.getFirst().query()).isEqualTo("q=ann%20%26%20bo&limit=5&api_key=k%261");
    }

    @Test
    void writesAreOffUntilTheConnectionAllowsThem() {
        Map<String, Object> post = Map.of("connection", "crm", "method", "POST", "path", "/customers", "body", "{\"name\":\"Cy\"}");

        assertThatThrownBy(() -> call(connection().build(), acme, post))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("POST isn't allowed");

        String result = call(connection().allowWrites().build(), acme, post);
        assertThat(result).startsWith("HTTP 200 POST /customers");
        assertThat(seen.getFirst().body()).isEqualTo("{\"name\":\"Cy\"}");
    }

    @Test
    void invalidJsonBodiesAndBodiesOnGetAreFixableErrors() {
        ApiConnection crm = connection().allowWrites().build();

        assertThatThrownBy(() -> call(crm, acme, Map.of("connection", "crm", "method", "POST", "path", "/x", "body", "{nope")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("valid JSON");
        assertThatThrownBy(() -> call(crm, acme, Map.of("connection", "crm", "method", "GET", "path", "/x", "body", "{}")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("can't have a body");
        assertThat(seen).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/../admin", "/customers/../../admin", "/%2e%2e/admin", "customers", "/customers?x=1",
            "/a\\b", "//evil.example/x", "/customers#frag"})
    void pathsThatCouldEscapeTheBaseUrlAreRefused(String path) {
        assertThatThrownBy(() -> call(connection().build(), acme, Map.of("connection", "crm", "method", "GET", "path", path)))
                .isInstanceOf(ToolInputException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void allowedPathPatternsAreEnforcedAndTrailingDoubleStarCoversTheListEndpoint() {
        ApiConnection crm = connection().allowedPaths("/customers/**").build();

        assertThat(call(crm, acme, Map.of("connection", "crm", "method", "GET", "path", "/customers"))).startsWith("HTTP 200");
        assertThat(call(crm, acme, Map.of("connection", "crm", "method", "GET", "path", "/customers/cus_1/notes"))).startsWith("HTTP 200");
        assertThatThrownBy(() -> call(crm, acme, Map.of("connection", "crm", "method", "GET", "path", "/invoices")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("isn't an allowed path");
    }

    @Test
    void errorStatusesAndRedirectsComeBackAsResults() {
        assertThat(call(connection().build(), acme, Map.of("connection", "crm", "method", "GET", "path", "/customers/missing")))
                .startsWith("HTTP 404").contains("No such customer");
        assertThat(call(connection().build(), acme, Map.of("connection", "crm", "method", "GET", "path", "/moved")))
                .startsWith("HTTP 301").contains("Location: https://elsewhere.example/");
    }

    @Test
    void longResponsesAreCutOffWithAHint() {
        String result = call(connection().maxResponseChars(10).build(), acme,
                Map.of("connection", "crm", "method", "GET", "path", "/big"));

        assertThat(result).contains("x".repeat(10) + "\n[Response cut off at 10 of 100 characters");
    }

    @Test
    void saveAsKeepsTheRawBodyInTheWorkspace() {
        call(connection().build(), acme, Map.of("connection", "crm", "method", "GET", "path", "/customers", "save_as", "exports/customers.json"));

        assertThat(acme.workspace().require("exports/customers.json").text()).contains("cus_2");
    }

    @Test
    void anUnreachableApiIsAnInfrastructureFailure() {
        ApiConnection dead = ApiConnection.builder("dead", "http://127.0.0.1:1/v1").build();

        assertThatThrownBy(() -> call(dead, acme, Map.of("connection", "dead", "method", "GET", "path", "/x")))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void connectionsValidateTheirConfiguration() {
        assertThatThrownBy(() -> ApiConnection.builder("bad name", base)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiConnection.builder("x", "ftp://example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiConnection.builder("x", "https://example.com/v1?key=1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiTools.request(List.of(connection().build(), connection().build())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    }

    @Test
    void theToolDescriptionListsEachConnectionsMethodsAndPaths() {
        var tool = ApiTools.request(List.of(connection().description("Customer records").allowedPaths("/customers/**").build()));

        assertThat(tool.definition().getDescription()).contains("crm: Customer records (methods: GET, HEAD; paths: /customers/**)");
    }

    private ApiConnection.Builder connection() {
        return ApiConnection.builder("crm", base);
    }

    private String call(ApiConnection connection, ToolContext context, Map<String, Object> args) {
        return ApiTools.request(List.of(connection)).handler().handle(new HashMap<>(args), context);
    }

    private static ToolContext context(String tenant) {
        Scope scope = new Scope(tenant, "user", null);
        return new ToolContext(UUID.randomUUID(), 0, Scope.of(tenant, "user", "s1"),
                MemoryStore.NONE.scopedTo(scope), new InMemoryWorkspace().scopedTo(scope, WorkspaceLimits.DEFAULT));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
