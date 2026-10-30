package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.ScopeLevel;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcedureToolsTest {

    private final ApiStub api = new ApiStub()
            .on("GET /v1/contacts", 200, "{\"data\":[{\"id\":\"c_1\"}]}")
            .on("POST /v1/contacts/c_1/notes", 201, "{\"id\":\"n_1\"}");
    private final AtomicReference<ApiConnection> crm = new AtomicReference<>(
            ApiConnection.builder("crm", api.url("/v1")).allowWrites().build());
    private final InMemoryProcedureStore store = new InMemoryProcedureStore();
    private final Map<String, RegisteredTool> tools = new HashMap<>();
    private final ToolContext acme = ApiStub.context("acme", "alice");

    ProcedureToolsTest() {
        ConnectionSource connections = scope -> scope.tenantId().equals("acme") ? List.of(crm.get()) : List.of();
        ProcedureTools.all(connections, store).forEach(t -> tools.put(t.definition().getName(), t));
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void aSavedProcedureIsListedShownAndRunWithArguments() {
        assertThat(call("procedure_save", addNote())).isEqualTo(
                "Saved add_contact_note as version 1 on crm (writes: POST /contacts/{{find.id}}/notes). Run it with procedure_run.");

        assertThat(call("procedure_list", Map.of("query", "note"))).contains(
                "- add_contact_note v1 (crm, writes): Find a contact by email and add a note",
                "parameters: email* (string) Contact email; text* (string)");
        assertThat(call("procedure_get", Map.of("name", "add_contact_note")))
                .contains("\"path\" : \"/contacts/{{find.id}}/notes\"", "Versions: 1 (");

        String result = call("procedure_run", Map.of("name", "add_contact_note", "arguments", List.of(
                Map.of("name", "email", "value", "ann@example.com"), Map.of("name", "text", "value", "Hello"))));

        assertThat(result).startsWith("Procedure add_contact_note v1 on crm: 2 steps succeeded").endsWith("{\"note\":\"n_1\"}");
        assertThat(api.seen.get(1).body()).isEqualTo("{\"body\":\"Hello\"}");
    }

    @Test
    void savingAnExistingNameMakesANewVersionAndKeepsTheOld() {
        call("procedure_save", addNote());
        Map<String, Object> fixed = new HashMap<>(addNote());
        fixed.put("output", "{\"note\": \"{{note.id}}\", \"contact\": \"{{find.id}}\"}");

        assertThat(call("procedure_save", fixed)).contains("as version 2").contains("Earlier versions are kept");

        Scope tenant = acme.scope().atLevel(ScopeLevel.TENANT);
        assertThat(store.versions(tenant, "add_contact_note")).extracting(Procedure::version).containsExactly(1, 2);
        assertThat(call("procedure_get", Map.of("name", "add_contact_note", "version", 1))).doesNotContain("contact\\\"");
        assertThat(call("procedure_run", Map.of("name", "add_contact_note", "version", 1,
                "arguments", Map.of("email", "a", "text", "b")))).endsWith("{\"note\":\"n_1\"}");
        assertThatThrownBy(() -> call("procedure_get", Map.of("name", "add_contact_note", "version", 3)))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("with version 3");
    }

    @Test
    void writesAreRefusedOnAReadOnlyConnectionWhenSavedAndWhenRun() {
        crm.set(ApiConnection.builder("crm", api.url("/v1")).build());
        assertThatThrownBy(() -> call("procedure_save", addNote())).isInstanceOf(ToolInputException.class)
                .hasMessageContaining("POST isn't allowed on crm, which is read-only");
        assertThat(store.versions(acme.scope().atLevel(ScopeLevel.TENANT), "add_contact_note")).isEmpty();

        crm.set(ApiConnection.builder("crm", api.url("/v1")).allowWrites().build());
        call("procedure_save", addNote());
        crm.set(ApiConnection.builder("crm", api.url("/v1")).build());   // writes switched off since

        assertThatThrownBy(() -> call("procedure_run", Map.of("name", "add_contact_note",
                "arguments", Map.of("email", "a", "text", "b")))).isInstanceOf(ToolInputException.class)
                .hasMessageContaining("read-only");
        assertThat(api.seen).isEmpty();
    }

    @Test
    void savingChecksPathsTheConnectionAllows() {
        crm.set(ApiConnection.builder("crm", api.url("/v1")).allowWrites().allowedPaths("/contacts").build());

        assertThatThrownBy(() -> call("procedure_save", addNote())).isInstanceOf(ToolInputException.class)
                .hasMessageContaining("Step 'note': /contacts/x/notes isn't an allowed path on crm");
    }

    @Test
    void badDefinitionsAndArgumentsAreFixableErrors() {
        Map<String, Object> unknownConnection = new HashMap<>(addNote());
        unknownConnection.put("connection", "billing");
        assertThatThrownBy(() -> call("procedure_save", unknownConnection)).hasMessageContaining("No connection called 'billing'");

        Map<String, Object> badRef = new HashMap<>(addNote());
        badRef.put("output", "{\"x\": \"{{nope.id}}\"}");
        assertThatThrownBy(() -> call("procedure_save", badRef)).isInstanceOf(ToolInputException.class)
                .hasMessageContaining("no earlier step is called 'nope'");

        call("procedure_save", addNote());
        assertThatThrownBy(() -> call("procedure_run", Map.of("name", "add_contact_note",
                "arguments", List.of(Map.of("name", "email", "value", "a")))))
                .isInstanceOf(ToolInputException.class).hasMessage("Missing required argument 'text' (string)");
        assertThatThrownBy(() -> call("procedure_run", Map.of("name", "add_contact_note", "arguments", List.of(
                Map.of("name", "email", "value", "a"), Map.of("name", "email", "value", "b")))))
                .hasMessage("Argument 'email' is given twice");
        assertThatThrownBy(() -> call("procedure_run", Map.of("name", "nothing")))
                .hasMessageContaining("No saved procedure called 'nothing'");
    }

    @Test
    void proceduresAreSharedWithinATenantAndNeverAcrossTenants() {
        call("procedure_save", addNote());

        assertThat(tools.get("procedure_list").handler().handle(new HashMap<>(), ApiStub.context("acme", "bob")))
                .contains("add_contact_note v1");
        ToolContext globex = ApiStub.context("globex", "alice");
        assertThat(tools.get("procedure_list").handler().handle(new HashMap<>(), globex)).isEqualTo("No saved procedures.");
        assertThatThrownBy(() -> tools.get("procedure_run").handler()
                .handle(new HashMap<>(Map.of("name", "add_contact_note")), globex))
                .hasMessageContaining("No saved procedure called 'add_contact_note'");
    }

    @Test
    void runAndSaveDeclareWritesFromTheProcedure() {
        call("procedure_save", addNote());
        call("procedure_save", Map.of("name", "find_contact", "connection", "crm", "description", "Find a contact",
                "steps", List.of(Map.of("name", "find", "method", "GET", "path", "/contacts"))));

        assertThat(tools.get("procedure_run").writes(Map.of("name", "add_contact_note"), acme)).isTrue();
        assertThat(tools.get("procedure_run").writes(Map.of("name", "find_contact"), acme)).isFalse();
        assertThat(tools.get("procedure_run").writes(Map.of("name", "unknown"), acme)).isFalse();
        assertThat(tools.get("procedure_save").writes(addNote(), acme)).isTrue();
        assertThat(tools.get("procedure_save").writes(Map.of("steps", List.of(Map.of("method", "get"))), acme)).isFalse();
        assertThat(tools.values()).allMatch(RegisteredTool::untrustedOutput);
    }

    private static Map<String, Object> addNote() {
        return Map.of(
                "name", "add_contact_note",
                "connection", "crm",
                "description", "Find a contact by email and add a note",
                "parameters", List.of(
                        Map.of("name", "email", "type", "string", "description", "Contact email"),
                        Map.of("name", "text", "type", "string")),
                "steps", List.of(
                        Map.of("name", "find", "method", "GET", "path", "/contacts",
                                "query", List.of(Map.of("name", "email", "value", "{{email}}")),
                                "keep", List.of(Map.of("name", "id", "pointer", "/data/0/id"))),
                        Map.of("name", "note", "method", "POST", "path", "/contacts/{{find.id}}/notes",
                                "body", "{\"body\": \"{{text}}\"}",
                                "keep", List.of(Map.of("name", "id", "pointer", "/id")))),
                "output", "{\"note\": \"{{note.id}}\"}");
    }

    private String call(String tool, Map<String, Object> args) {
        return tools.get(tool).handler().handle(new HashMap<>(args), acme);
    }
}
