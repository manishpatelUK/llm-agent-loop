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

class ApiBodyTypesTest {

    private final ApiStub api = new ApiStub().on("POST /v1/customers", 200, "{\"id\":\"cus_1\"}");
    private final ToolContext acme = ApiStub.context("acme");

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void formFieldsAreSentFormEncoded() {
        String result = call(json(), Map.of("connection", "billing", "method", "POST", "path", "/customers",
                "form", List.of(Map.of("name", "email", "value", "a+b@example.com"), Map.of("name", "metadata[plan]", "value", "pro team"))));

        assertThat(result).startsWith("HTTP 200 POST /customers");
        assertThat(api.seen.getFirst().contentType()).isEqualTo("application/x-www-form-urlencoded; charset=utf-8");
        assertThat(api.seen.getFirst().body()).isEqualTo("email=a%2Bb%40example.com&metadata%5Bplan%5D=pro+team");
    }

    @Test
    void aFormConnectionRefusesRawBodyTextUnlessTheCallChoosesAnotherType() {
        ApiConnection stripe = ApiConnection.builder("billing", api.url("/v1")).allowWrites().bodyType(ApiBodyType.FORM).build();

        assertThatThrownBy(() -> call(stripe, Map.of("connection", "billing", "method", "POST", "path", "/customers",
                "body", "email=a@b.com"))).isInstanceOf(ToolInputException.class)
                .hasMessageContaining("Form bodies are given as 'form' name/value pairs");

        call(stripe, Map.of("connection", "billing", "method", "POST", "path", "/customers",
                "body", "{\"email\":\"a@b.com\"}", "content_type", "application/json"));
        assertThat(api.seen.getFirst().contentType()).startsWith("application/json");
        assertThat(api.seen.getFirst().body()).isEqualTo("{\"email\":\"a@b.com\"}");
    }

    @Test
    void plainTextBodiesAreSentAsTheyAre() {
        call(json(), Map.of("connection", "billing", "method", "POST", "path", "/customers",
                "body", "not { json", "content_type", "text/plain"));

        assertThat(api.seen.getFirst().contentType()).isEqualTo("text/plain; charset=utf-8");
        assertThat(api.seen.getFirst().body()).isEqualTo("not { json");
    }

    @Test
    void mismatchedOrUnknownEncodingsAreFixableErrors() {
        ApiConnection billing = json();

        assertThatThrownBy(() -> call(billing, Map.of("connection", "billing", "method", "POST", "path", "/customers",
                "form", List.of(Map.of("name", "a", "value", "1")), "content_type", "application/json")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("'form' fields need content_type");
        assertThatThrownBy(() -> call(billing, Map.of("connection", "billing", "method", "POST", "path", "/customers",
                "body", "{}", "content_type", "application/xml")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("'content_type' must be one of");
        assertThatThrownBy(() -> call(billing, Map.of("connection", "billing", "method", "POST", "path", "/customers",
                "body", "{nope")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("'body' must be valid JSON");
        assertThatThrownBy(() -> call(billing, Map.of("connection", "billing", "method", "GET", "path", "/customers",
                "form", List.of(Map.of("name", "a", "value", "1")))))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("can't have a body");
        assertThatThrownBy(() -> call(billing, Map.of("connection", "billing", "method", "POST", "path", "/customers",
                "form", "a=1"))).isInstanceOf(ToolInputException.class).hasMessageContaining("list of {name, value}");
        assertThat(api.seen).isEmpty();
    }

    @Test
    void theFixedListRosterMentionsANonJsonDefault() {
        var tool = ApiTools.request(List.of(ApiConnection.builder("billing", api.url("/v1")).bodyType(ApiBodyType.FORM).build()));

        assertThat(tool.definition().getDescription())
                .contains("billing (methods: GET, HEAD; paths: /**; bodies: application/x-www-form-urlencoded)");
    }

    private ApiConnection json() {
        return ApiConnection.builder("billing", api.url("/v1")).allowWrites().build();
    }

    private String call(ApiConnection connection, Map<String, Object> args) {
        return ApiTools.request(List.of(connection)).handler().handle(new HashMap<>(args), acme);
    }
}
