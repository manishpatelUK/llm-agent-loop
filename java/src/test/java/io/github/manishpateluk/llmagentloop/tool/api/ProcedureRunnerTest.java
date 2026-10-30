package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcedureRunnerTest {

    private final ApiStub api = new ApiStub()
            .on("GET /v1/contacts", 200, "{\"data\":[{\"id\":\"c_1\",\"name\":\"Ann\"}]}")
            .on("POST /v1/contacts/c_1/notes", 201, "{\"id\":\"n_9\"}")
            .on("GET /v1/admin", 200, "{\"secret\":true}");
    private final Scope acme = Scope.of("acme", "alice", "s1");

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void stepsRunInOrderFillingTemplatesFromArgumentsAndKeptValues() {
        Procedure addNote = Procedure.builder("add_note", "crm")
                .parameter(Procedure.Parameter.required("email", "string", ""))
                .parameter(Procedure.Parameter.required("text", "string", ""))
                .parameter(Procedure.Parameter.optional("priority", "integer", ""))
                .parameter(Procedure.Parameter.optional("source", "string", ""))
                .step(ProcedureStep.builder("find", "GET", "/contacts").query("email", "{{email}}").query("source", "{{source}}")
                        .keep("id", "/data/0/id").keep("contact", "/data/0").build())
                .step(ProcedureStep.builder("note", "POST", "/contacts/{{find.id}}/notes")
                        .body("{\"text\": \"{{text}}\", \"priority\": \"{{priority}}\", \"about\": \"{{find.contact}}\", "
                                + "\"label\": \"Note for {{find.id}}\"}")
                        .keep("id", "/id").build())
                .output("{\"contact\": \"{{find.id}}\", \"note\": \"{{note.id}}\"}")
                .build();

        ProcedureResult result = runner(connection().allowWrites().build())
                .run(acme, addNote, Map.of("email", "ann@example.com", "text", "Said \"yes\"", "priority", "2"));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).isEqualTo("{\"contact\":\"c_1\",\"note\":\"n_9\"}");
        assertThat(api.seen.get(0).query()).isEqualTo("email=ann%40example.com");   // optional 'source' left out
        assertThat(api.seen.get(1).body()).isEqualTo(
                "{\"text\":\"Said \\\"yes\\\"\",\"priority\":2,\"about\":{\"id\":\"c_1\",\"name\":\"Ann\"},\"label\":\"Note for c_1\"}");
        assertThat(result.steps()).extracting(ProcedureResult.Step::status).containsExactly(200, 201);
        assertThat(result.describe()).startsWith("Procedure add_note v0 on crm: 2 steps succeeded");
    }

    @Test
    void aFailingStepStopsTheRunAndReportsItsStatusAndBody() {
        api.on("POST /v1/contacts/c_1/notes", 422, "{\"error\":\"text is too long\"}");
        Procedure three = Procedure.builder("three", "crm")
                .step(ProcedureStep.builder("find", "GET", "/contacts").keep("id", "/data/0/id").build())
                .step(ProcedureStep.builder("note", "POST", "/contacts/{{find.id}}/notes").body("{\"text\":\"x\"}").build())
                .step(ProcedureStep.builder("tag", "POST", "/contacts/{{find.id}}/tags").body("{}").build())
                .build();

        ProcedureResult result = runner(connection().allowWrites().build()).run(acme, three, Map.of());

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failure()).isEqualTo(new ProcedureResult.Failure("note", 2, "POST", "/contacts/c_1/notes", 422,
                "The API answered 422.", "{\"error\":\"text is too long\"}"));
        assertThat(api.seen).extracting(ApiStub.Seen::path).containsExactly("/v1/contacts", "/v1/contacts/c_1/notes");
        assertThat(result.writesMade()).isEmpty();
        assertThat(result.describe()).contains("failed at step 2 of 3 (note): HTTP 422 POST /contacts/c_1/notes",
                "text is too long", "Steps before it ran: find: GET /contacts 200", "Later steps weren't run");
    }

    @Test
    void aValueMissingFromAResponseIsAFailureOfThatStep() {
        api.on("GET /v1/contacts", 200, "{\"data\":[]}");
        Procedure p = Procedure.builder("p", "crm")
                .step(ProcedureStep.builder("find", "GET", "/contacts").keep("id", "/data/0/id").build())
                .step(ProcedureStep.builder("get", "GET", "/contacts/{{find.id}}").build())
                .build();

        ProcedureResult result = runner(connection().build()).run(acme, p, Map.of());

        assertThat(result.failure().reason()).isEqualTo("Nothing at /data/0/id in the response, for 'id'.");
        assertThat(result.failure().body()).isEqualTo("{\"data\":[]}");
        assertThat(api.seen).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../admin", "..", "c_1/../../admin", "%2e%2e", ".", "x/../../../admin"})
    void anArgumentCannotEscapeThePathItIsPutIn(String id) {
        Procedure p = Procedure.builder("get_contact", "crm")
                .parameter(Procedure.Parameter.required("id", "string", ""))
                .step(ProcedureStep.builder("get", "GET", "/contacts/{{id}}").build())
                .build();

        ProcedureResult result = runner(connection().allowedPaths("/contacts/**").build()).run(acme, p, Map.of("id", id));

        // Refused before sending, or sent as one encoded segment under /contacts: never as path structure.
        assertThat(api.seen).allSatisfy(s -> assertThat(s.path()).startsWith("/v1/contacts/").doesNotContain("/..", "/v1/admin"));
        if (!result.succeeded() && api.seen.isEmpty()) {
            assertThat(result.failure().status()).isZero();
            assertThat(result.failure().reason()).containsAnyOf("'.' or '..' segments", "A path value can't be");
        }
    }

    @Test
    void valuesThatLookLikePathStructureStayInsideOneSegment() {
        Procedure p = Procedure.builder("get_contact", "crm")
                .parameter(Procedure.Parameter.required("id", "string", ""))
                .step(ProcedureStep.builder("get", "GET", "/contacts/{{id}}").build())
                .build();

        runner(connection().build()).run(acme, p, Map.of("id", "c_1?expand=all#x"));

        assertThat(api.seen.getFirst().path()).isEqualTo("/v1/contacts/c_1%3Fexpand%3Dall%23x");
        assertThat(api.seen.getFirst().query()).isNull();
    }

    @Test
    void aWritingProcedureIsRefusedBeforeAnythingIsSentOnAReadOnlyConnection() {
        Procedure p = Procedure.builder("add_note", "crm")
                .step(ProcedureStep.builder("find", "GET", "/contacts").keep("id", "/data/0/id").build())
                .step(ProcedureStep.builder("note", "POST", "/contacts/{{find.id}}/notes").body("{}").build())
                .build();

        assertThatThrownBy(() -> runner(connection().build()).run(acme, p, Map.of()))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("Step 'note': POST isn't allowed on crm, which is read-only; allowed: [GET, HEAD]");
        assertThatThrownBy(() -> runner(connection().allowWrites().allowedPaths("/contacts").build()).run(acme, p, Map.of()))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("Step 'note': /contacts/x/notes isn't an allowed path");
        assertThat(api.seen).isEmpty();
    }

    @Test
    void theConnectionIsLookedUpForTheRunsScopeAndArgumentsAreCheckedFirst() {
        Procedure p = Procedure.builder("p", "crm")
                .parameter(Procedure.Parameter.required("email", "string", ""))
                .step(ProcedureStep.builder("find", "GET", "/contacts").query("email", "{{email}}").build())
                .build();
        ProcedureRunner runner = new ProcedureRunner(scope -> scope.tenantId().equals("acme") ? List.of(connection().build()) : List.of());

        assertThatThrownBy(() -> runner.run(acme, p, Map.of())).hasMessageContaining("Missing required argument 'email'");
        assertThatThrownBy(() -> runner.run(Scope.of("globex", "bob", "s"), p, Map.of("email", "a")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("connection 'crm', which isn't set up for this user");
        assertThat(runner.run(acme, p, Map.of("email", "a")).output()).isEqualTo("{\"data\":[{\"id\":\"c_1\",\"name\":\"Ann\"}]}");
    }

    @Test
    void formAndTextBodiesAreFilledInAsWell() {
        api.on("POST /v1/customers", 200, "{\"id\":\"cus_1\"}").on("POST /v1/notes", 200, "{}");
        Procedure p = Procedure.builder("create", "crm")
                .parameter(Procedure.Parameter.required("email", "string", ""))
                .parameter(Procedure.Parameter.optional("plan", "string", ""))
                .step(ProcedureStep.builder("customer", "POST", "/customers").form("email", "{{email}}").form("metadata[plan]", "{{plan}}")
                        .keep("id", "/id").build())
                .step(ProcedureStep.builder("note", "POST", "/notes").body(ApiBodyType.TEXT, "Created {{customer.id}} for {{email}}").build())
                .build();

        runner(connection().allowWrites().build()).run(acme, p, Map.of("email", "a@b.com"));

        assertThat(api.seen.get(0).body()).isEqualTo("email=a%40b.com");
        assertThat(api.seen.get(0).contentType()).startsWith("application/x-www-form-urlencoded");
        assertThat(api.seen.get(1).body()).isEqualTo("Created cus_1 for a@b.com");
        assertThat(api.seen.get(1).contentType()).startsWith("text/plain");
    }

    private ApiConnection.Builder connection() {
        return ApiConnection.builder("crm", api.url("/v1"));
    }

    private static ProcedureRunner runner(ApiConnection connection) {
        return new ProcedureRunner(ConnectionSource.of(List.of(connection)));
    }
}
