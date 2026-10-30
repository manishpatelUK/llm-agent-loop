package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiDescribeTest {

    private static final String OPENAPI = """
            {"openapi": "3.0.0",
             "servers": [{"url": "https://api.example.com/v1"}],
             "paths": {
               "/contacts": {
                 "get": {"summary": "List contacts", "tags": ["Contacts"],
                         "parameters": [{"name": "email", "in": "query"}, {"$ref": "#/components/parameters/Limit"},
                                        {"name": "X-Trace", "in": "header"}]},
                 "post": {"summary": "Create a contact",
                          "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Contact"}}}}}
               },
               "/contacts/{id}/notes": {
                 "parameters": [{"name": "id", "in": "path", "required": true}],
                 "get": {"description": "List a contact's notes.\\nPaged.", "operationId": "listNotes"}
               },
               "/invoices": {"get": {"summary": "List invoices"}}
             },
             "components": {
               "parameters": {"Limit": {"name": "limit", "in": "query"}},
               "schemas": {"Contact": {"type": "object", "required": ["email"],
                                       "properties": {"email": {"type": "string"}, "name": {"type": "string"}}}}
             }}
            """;

    private final ApiStub api = new ApiStub();
    private final ToolContext acme = ApiStub.context("acme");

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void listsTheEndpointsTheConnectionAllowsRelativeToItsBaseUrl() {
        api.on("GET /v1/openapi.json", seen -> "Bearer k".equals(seen.auth())
                ? ApiStub.Reply.json(200, OPENAPI) : ApiStub.Reply.json(401, "{}"));
        ApiConnection crm = ApiConnection.builder("crm", api.url("/v1")).auth(ApiAuth.bearer(scope -> "k"))
                .allowedPaths("/contacts/**").openApiSpec("/openapi.json").build();

        String result = describe(crm, Map.of("connection", "crm"));

        assertThat(result).startsWith("2 endpoints on crm (2 more aren't allowed on this connection), showing 1-2.")
                .contains("GET /contacts - List contacts\n    parameters: email (query), limit (query)",
                        "GET /contacts/{id}/notes - List a contact's notes.\n    parameters: id* (path)")
                .doesNotContain("POST", "/invoices", "X-Trace");

        ApiConnection writable = ApiConnection.builder("crm", api.url("/v1")).auth(ApiAuth.bearer(scope -> "k"))
                .allowWrites().openApiSpec("/openapi.json").build();
        assertThat(describe(writable, Map.of("connection", "crm", "query", "create contact")))
                .contains("POST /contacts - Create a contact\n    body: email*, name").doesNotContain("GET /contacts");
        assertThat(describe(writable, Map.of("connection", "crm", "query", "nothing-like-this")))
                .isEqualTo("No endpoints match 'nothing-like-this' among the 4 crm allows.");
    }

    @Test
    void aSpecHostedElsewhereIsFetchedWithoutCredentials() {
        api.on("GET /docs/openapi.json", 200, OPENAPI);
        ApiConnection crm = ApiConnection.builder("crm", api.url("/v1")).auth(ApiAuth.bearer(scope -> "k"))
                .openApiSpec(api.url("/docs/openapi.json")).build();

        var tool = ApiTools.describe(ConnectionSource.of(List.of(crm)));
        tool.handler().handle(new HashMap<>(Map.of("connection", "crm")), acme);
        assertThat(tool.handler().handle(new HashMap<>(Map.of("connection", "crm", "query", "invoices")), acme))
                .contains("GET /invoices - List invoices");

        assertThat(api.seen).hasSize(1);   // cached
        assertThat(api.seen.getFirst().auth()).isNull();
    }

    @Test
    void longListsArePagedAndUnusableSpecsAreFixableErrors() {
        StringBuilder paths = new StringBuilder();
        for (int i = 0; i < 45; i++) {
            paths.append(i == 0 ? "" : ",").append("\"/r").append(i).append("\": {\"get\": {\"summary\": \"R").append(i).append("\"}}");
        }
        api.on("GET /v1/big.json", 200, "{\"swagger\": \"2.0\", \"basePath\": \"/v1\", \"paths\": {" + paths + "}}");
        api.on("GET /v1/spec.yaml", seen -> new ApiStub.Reply(200, "application/yaml", "openapi: 3.0.0"));
        ApiConnection big = ApiConnection.builder("big", api.url("/v1")).openApiSpec("/big.json").build();

        assertThat(describe(big, Map.of("connection", "big"))).contains("showing 1-40", "GET /r0 - R0", "[5 more: call again with offset 40.]");
        assertThat(describe(big, Map.of("connection", "big", "offset", 40))).contains("showing 41-45", "GET /r44 - R44");

        assertThatThrownBy(() -> describe(ApiConnection.builder("yaml", api.url("/v1")).openApiSpec("/spec.yaml").build(),
                Map.of("connection", "yaml"))).isInstanceOf(ToolInputException.class).hasMessageContaining("isn't JSON");
        assertThatThrownBy(() -> describe(ApiConnection.builder("none", api.url("/v1")).build(), Map.of("connection", "none")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("has no OpenAPI description configured");
        assertThatThrownBy(() -> ApiConnection.builder("x", api.url("/v1")).openApiSpec("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String describe(ApiConnection connection, Map<String, Object> args) {
        return ApiTools.describe(ConnectionSource.of(List.of(connection))).handler().handle(new HashMap<>(args), ApiStub.context("acme"));
    }
}
