package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcedureTest {

    @Test
    void templateReferencesAreCheckedWhenAProcedureIsMade() {
        assertThatThrownBy(() -> procedure(ProcedureStep.builder("find", "GET", "/contacts/{{nobody}}").build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{{nobody}}, which isn't a parameter");
        assertThatThrownBy(() -> procedure(
                ProcedureStep.builder("add", "POST", "/contacts/{{find.id}}/notes").body("{}").build(),
                ProcedureStep.builder("find", "GET", "/contacts").keep("id", "/data/0/id").build()))
                .hasMessageContaining("no earlier step is called 'find'");
        assertThatThrownBy(() -> procedure(
                ProcedureStep.builder("find", "GET", "/contacts").keep("id", "/data/0/id").build(),
                ProcedureStep.builder("get", "GET", "/contacts/{{find.uuid}}").build()))
                .hasMessageContaining("step 'find' doesn't keep 'uuid' (it keeps: id)");
        assertThatThrownBy(() -> procedure(ProcedureStep.builder("find", "GET", "/contacts/{{note}}").build()))
                .hasMessageContaining("which is optional; paths need required parameters");
        assertThatThrownBy(() -> procedure(ProcedureStep.builder("find", "GET", "/contacts").query("q", "{{ email address }}").build()))
                .hasMessageContaining("has a placeholder that isn't {{name}} or {{step.value}}");
        assertThatThrownBy(() -> procedure(ProcedureStep.builder("add", "POST", "/contacts").body("{\"email\": {{email}}}").build()))
                .hasMessageContaining("must be valid JSON");
        assertThatThrownBy(() -> procedure(ProcedureStep.builder("email", "GET", "/contacts").build()))
                .hasMessageContaining("is used twice");
        assertThatThrownBy(() -> procedure(ProcedureStep.builder("find", "GET", "/contacts").body("{}").build()))
                .hasMessageContaining("GET requests can't have a body");
        assertThatThrownBy(() -> procedure(ProcedureStep.builder("find", "GET", "/contacts").keep("id", "data/0").build()))
                .hasMessageContaining("isn't a JSON Pointer");
        assertThatThrownBy(() -> Procedure.builder("bad name", "crm").step(ProcedureStep.builder("a", "GET", "/").build()).build())
                .hasMessageContaining("Procedure name must match");
        assertThatThrownBy(() -> Procedure.builder("empty", "crm").build()).hasMessageContaining("between 1 and 20 steps");
    }

    @Test
    void theInputSchemaIsASmallScalarSubset() {
        ProcedureStep step = ProcedureStep.builder("find", "GET", "/contacts").build();
        Map<String, Object> nested = Map.of("type", "object", "properties", Map.of("tags", Map.of("type", "array")), "required", List.of());
        Map<String, Object> pattern = Map.of("type", "object",
                "properties", Map.of("email", Map.of("type", "string", "pattern", ".*")), "required", List.of());

        assertThatThrownBy(() -> new Procedure("p", 0, "crm", "", nested, List.of(step), null, null))
                .hasMessageContaining("must have type string, integer, number, boolean");
        assertThatThrownBy(() -> new Procedure("p", 0, "crm", "", pattern, List.of(step), null, null))
                .hasMessageContaining("uses 'pattern', which isn't supported");
        assertThatThrownBy(() -> new Procedure("p", 0, "crm", "", Map.of("type", "object", "properties", Map.of(),
                "required", List.of("x")), List.of(step), null, null)).hasMessageContaining("required 'x' isn't a property");
        assertThatThrownBy(() -> new Procedure.Parameter("limit", "integer", "", false, List.of(), "ten"))
                .hasMessageContaining("must be a whole number");
    }

    @Test
    void argumentsAreValidatedCoercedAndDefaulted() {
        Procedure p = Procedure.builder("search", "crm")
                .parameter(Procedure.Parameter.required("email", "string", "Contact email"))
                .parameter(new Procedure.Parameter("limit", "integer", "", false, List.of(), 10))
                .parameter(new Procedure.Parameter("status", "string", "", false, List.of("open", "closed"), null))
                .parameter(Procedure.Parameter.optional("vip", "boolean", ""))
                .parameter(Procedure.Parameter.optional("amount", "number", ""))
                .step(ProcedureStep.builder("find", "GET", "/contacts").query("email", "{{email}}").build())
                .build();

        assertThat(p.checkArguments(Map.of("email", "a@b.com", "limit", "25", "vip", "true", "amount", "12.50")))
                .hasToString("{email=\"a@b.com\", limit=25, vip=true, amount=12.50}");
        assertThat(p.checkArguments(Map.of("email", "a@b.com")).get("limit").asLong()).isEqualTo(10);

        assertThatThrownBy(() -> p.checkArguments(Map.of())).isInstanceOf(ToolInputException.class)
                .hasMessage("Missing required argument 'email' (string)");
        assertThatThrownBy(() -> p.checkArguments(Map.of("email", "a", "tenant", "globex")))
                .isInstanceOf(ToolInputException.class)
                .hasMessage("Unknown argument 'tenant'; search takes: email, limit, status, vip, amount");
        assertThatThrownBy(() -> p.checkArguments(Map.of("email", "a", "limit", "lots"))).hasMessage("Argument 'limit' must be a whole number");
        assertThatThrownBy(() -> p.checkArguments(Map.of("email", "a", "limit", 2.5))).hasMessage("Argument 'limit' must be a whole number");
        assertThatThrownBy(() -> p.checkArguments(Map.of("email", "a", "status", "pending")))
                .hasMessage("Argument 'status' must be one of [open, closed], not 'pending'");
        assertThatThrownBy(() -> p.checkArguments(Map.of("email", "a", "vip", "yes"))).hasMessage("Argument 'vip' must be true or false");
        assertThatThrownBy(() -> p.checkArguments(Map.of("email", "a", "amount", "12,50"))).hasMessage("Argument 'amount' must be a number");
        assertThatThrownBy(() -> p.checkArguments(Map.of("email", List.of("a")))).hasMessage("Argument 'email' must be a string");
    }

    @Test
    void writesIsDerivedFromTheStepsMethods() {
        ProcedureStep find = ProcedureStep.builder("find", "GET", "/contacts").keep("id", "/data/0/id").build();
        ProcedureStep remove = ProcedureStep.builder("remove", "delete", "/contacts/{{find.id}}").build();

        assertThat(procedure(find).writes()).isFalse();
        assertThat(procedure(find, remove).writes()).isTrue();
        assertThat(remove.method()).isEqualTo("DELETE");
    }

    @Test
    void aProcedureSurvivesAJsonRoundTrip() {
        Procedure p = Procedure.builder("add_note", "crm")
                .description("Add a note")
                .parameter(Procedure.Parameter.required("email", "string", "Contact email"))
                .parameter(new Procedure.Parameter("pinned", "boolean", "", false, List.of(), false))
                .step(ProcedureStep.builder("find", "GET", "/contacts").query("email", "{{email}}").keep("id", "/data/0/id").build())
                .step(ProcedureStep.builder("note", "POST", "/contacts/{{find.id}}/notes").form("pinned", "{{pinned}}").build())
                .output("{\"contact\": \"{{find.id}}\"}")
                .build()
                .withVersion(3, java.time.Instant.parse("2026-10-30T10:15:30Z"));

        Procedure back = Procedure.fromJson(p.toJson());

        assertThat(back).isEqualTo(p);
        assertThat(back.steps().get(1).bodyType()).isEqualTo(ApiBodyType.FORM);
        assertThat(p.toJson()).doesNotContain("\"writes\"");
        assertThatThrownBy(() -> Procedure.fromJson("{\"name\":\"x\"}")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Procedure procedure(ProcedureStep... steps) {
        Procedure.Builder builder = Procedure.builder("p", "crm")
                .parameter(Procedure.Parameter.required("email", "string", ""))
                .parameter(Procedure.Parameter.optional("note", "string", ""));
        for (ProcedureStep step : steps) {
            builder.step(step);
        }
        return builder.build();
    }
}
