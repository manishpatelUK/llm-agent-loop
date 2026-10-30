package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.ScopeLevel;
import io.github.manishpateluk.llmagentloop.skill.Skill;
import io.github.manishpateluk.llmagentloop.skill.Skills;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.testing.TestRuns;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolDecision;
import io.github.manishpateluk.llmagentloop.tool.ToolInterceptor;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.UntrustedContentGuard;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.manishpateluk.llmagentloop.testing.MockModel.lastToolResult;
import static org.assertj.core.api.Assertions.assertThat;

class ProcedureSkillTest {

    private static final Scope ACME = Scope.of("acme", "alice", "s1");

    private final ApiStub api = new ApiStub()
            .on("GET /v1/contacts", 200, "{\"data\":[{\"id\":\"c_1\"}]}")
            .on("POST /v1/contacts/c_1/notes", 201, "{\"id\":\"n_1\"}");
    private final ConnectionSource connections = scope -> scope.tenantId().equals("acme")
            ? List.of(ApiConnection.builder("crm", api.url("/v1")).description("Acme's CRM").allowWrites().build())
            : List.of();
    private final InMemoryProcedureStore store = new InMemoryProcedureStore();
    private final Skill skill = Skills.apiProcedures(connections, store);

    @AfterEach
    void tearDown() {
        api.close();
    }

    @Test
    void theModelExploresSavesAndRunsAProcedureWithWritesSentForApproval() {
        List<String> approvals = new CopyOnWriteArrayList<>();
        List<String> writes = new CopyOnWriteArrayList<>();
        MockModel model = new MockModel()
                .callTool("procedure_list", Map.of())
                .callTool("api_connections", Map.of())
                .callTool("api_request", Map.of("connection", "crm", "method", "GET", "path", "/contacts"))
                .callTool("procedure_save", addNote())
                .callTool("procedure_run", Map.of("name", "add_note", "arguments", List.of(Map.of("name", "text", "value", "Hi"))))
                .reply("Added the note.");
        ToolInterceptor recordWrites = new ToolInterceptor() {
            @Override
            public ToolDecision before(ToolCall call, ToolContext context) {
                writes.add(call.getName() + "=" + context.writes(call));
                return ToolDecision.proceed();
            }
        };

        TestRuns.TestRun run = TestRuns.run(model.loopBuilder().tools(registry())
                .toolInterceptor(recordWrites)
                .toolInterceptor(UntrustedContentGuard.requireApproval((call, context) -> approvals.add(call.getName())))
                .build(), request());

        assertThat(run.answer()).isEqualTo("Added the note.");
        assertThat(lastToolResult(model.requests().get(2))).contains("- crm: Acme's CRM");
        assertThat(lastToolResult(model.requests().get(5))).contains("2 steps succeeded", "n_1");
        assertThat(writes).containsExactly("procedure_list=false", "api_connections=false", "api_request=false",
                "procedure_save=true", "procedure_run=true");
        assertThat(approvals).containsExactly("procedure_save", "procedure_run");
        assertThat(store.latest(ACME.atLevel(ScopeLevel.TENANT), "add_note")).isPresent();
        assertThat(skill.instructionsSection()).startsWith("### APIs and procedures").contains("save it with procedure_save");
        assertThat(skill.tools()).extracting(t -> t.definition().getName()).containsExactly("api_connections", "api_request",
                "api_describe", "procedure_list", "procedure_get", "procedure_run", "procedure_save", "web_fetch");
    }

    @Test
    void afterReadingOutsideContentTheGuardHoldsBackOnlyProceduresThatWrite() {
        store.save(ACME.atLevel(ScopeLevel.TENANT), Procedure.builder("find", "crm")
                .step(ProcedureStep.builder("find", "GET", "/contacts").build()).build());
        store.save(ACME.atLevel(ScopeLevel.TENANT), Procedure.builder("add_note", "crm")
                .step(ProcedureStep.builder("note", "POST", "/contacts/c_1/notes").body("{}").build()).build());
        MockModel model = new MockModel()
                .callTool("api_request", Map.of("connection", "crm", "method", "GET", "path", "/contacts"))
                .callTool("procedure_run", Map.of("name", "find"))
                .callTool("procedure_run", Map.of("name", "add_note"))
                .callTool("procedure_save", addNote())
                .reply("I couldn't add the note.");

        TestRuns.run(model.loopBuilder().tools(registry()).toolInterceptor(UntrustedContentGuard.block()).build(), request());

        assertThat(lastToolResult(model.requests().get(2))).contains("1 step succeeded");
        assertThat(lastToolResult(model.requests().get(3))).contains("This action wasn't approved", "api_request");
        assertThat(lastToolResult(model.requests().get(4))).contains("This action wasn't approved");
        assertThat(api.seen).extracting(ApiStub.Seen::method).containsOnly("GET");
        assertThat(store.versions(ACME.atLevel(ScopeLevel.TENANT), "add_note")).hasSize(1);
    }

    private ToolRegistry registry() {
        return new ToolRegistry().registerAll(skill.tools());
    }

    private static LoopRequest.LoopRequestBuilder request() {
        return LoopRequest.builder().prompt("Add a note to Ann").scope(ACME).agentProfile(MockModel.STEP_BY_STEP);
    }

    private static Map<String, Object> addNote() {
        return Map.of("name", "add_note", "connection", "crm", "description", "Add a note to the first contact",
                "parameters", List.of(Map.of("name", "text", "type", "string")),
                "steps", List.of(
                        Map.of("name", "find", "method", "GET", "path", "/contacts",
                                "keep", List.of(Map.of("name", "id", "pointer", "/data/0/id"))),
                        Map.of("name", "note", "method", "POST", "path", "/contacts/{{find.id}}/notes",
                                "body", "{\"body\": \"{{text}}\"}")));
    }
}
