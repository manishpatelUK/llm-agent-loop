package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.testing.TestRuns;
import io.github.manishpateluk.llmagentloop.testing.TestRuns.TestRun;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.tool.builtin.WorkspaceTools;
import io.github.manishpateluk.llmagentloop.tool.web.WebTools;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tools and parameters that need a workspace are only offered when the loop has one. */
class WorkspaceAvailabilityTest {

    @Test
    void withoutAWorkspaceTheWorkspaceToolsAndSaveAsAreNotOffered() {
        MockModel model = new MockModel().reply("done");

        TestRuns.run(model.loopBuilder().tools(tools()).build(),
                LoopRequest.builder().prompt("go").agentProfile(MockModel.STEP_BY_STEP));

        assertThat(MockModel.toolsOffered(model.requests().getFirst()))
                .contains(WebTools.FETCH)
                .doesNotContain(WorkspaceTools.LIST, WorkspaceTools.READ, WorkspaceTools.WRITE, WorkspaceTools.EDIT,
                        WorkspaceTools.DELETE, WorkspaceTools.SEARCH, WorkspaceTools.VIEW);
        ToolDefinition fetch = offered(model, WebTools.FETCH);
        assertThat(properties(fetch)).doesNotContainKey("save_as").containsKey("url");
        assertThat(fetch.getDescription()).doesNotContain("save_as");
    }

    @Test
    void withAWorkspaceTheyAre() {
        MockModel model = new MockModel().reply("done");

        TestRuns.run(model.loopBuilder().tools(tools()).workspace(new InMemoryWorkspace()).build(),
                LoopRequest.builder().prompt("go").agentProfile(MockModel.STEP_BY_STEP));

        assertThat(MockModel.toolsOffered(model.requests().getFirst()))
                .contains(WebTools.FETCH, WorkspaceTools.LIST, WorkspaceTools.READ, WorkspaceTools.WRITE, WorkspaceTools.EDIT,
                        WorkspaceTools.DELETE, WorkspaceTools.SEARCH, WorkspaceTools.VIEW);
        ToolDefinition fetch = offered(model, WebTools.FETCH);
        assertThat(properties(fetch)).containsKey("save_as");
        assertThat(fetch.getDescription()).contains("save_as");
    }

    @Test
    void aWorkspaceToolCalledAnywayWithoutOneIsAFixableError() {
        MockModel model = new MockModel()
                .callTool(WorkspaceTools.WRITE, Map.of("path", "notes.md", "content", "hello"))
                .reply("couldn't save it");

        TestRun run = TestRuns.run(model.loopBuilder().tools(tools()).build(),
                LoopRequest.builder().prompt("go").agentProfile(MockModel.STEP_BY_STEP));

        assertThat(run.error()).isNull();
        assertThat(MockModel.lastToolResult(model.requests().getLast())).startsWith("Error: No workspace is configured");
        assertThat(run.result().finalResponse().getContent()).isEqualTo("couldn't save it");
    }

    @Test
    void anyToolCanDeclareItsWorkspaceNeeds() {
        RegisteredTool export = new RegisteredTool(ToolDefinition.builder().name("export").description("Export a report.")
                .parameters(ToolSchemas.object(List.of("id"),
                        "id", ToolSchemas.string("Report id."),
                        "save_as", ToolSchemas.string("Workspace path.")))
                .build(), (args, context) -> "ok");

        RegisteredTool optional = export.withWorkspaceParameters(List.of("save_as"), "Export a report as text.");
        assertThat(properties(optional.definitionFor(false).orElseThrow())).containsOnlyKeys("id");
        assertThat(optional.definitionFor(false).orElseThrow().getDescription()).isEqualTo("Export a report as text.");
        assertThat(optional.definitionFor(true)).contains(export.definition());
        assertThat(optional.withTimeout(java.time.Duration.ofSeconds(1)).definitionFor(false))
                .isEqualTo(optional.definitionFor(false));

        assertThat(export.withRequiresWorkspace().definitionFor(false)).isEmpty();
        assertThat(export.definitionFor(false)).contains(export.definition());

        assertThatThrownBy(() -> export.withWorkspaceParameters(List.of("id"), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> export.withWorkspaceParameters(List.of("nope"), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no parameter nope");
    }

    private static ToolRegistry tools() {
        return new ToolRegistry().registerAll(WorkspaceTools.all()).register(WebTools.fetch());
    }

    private static ToolDefinition offered(MockModel model, String name) {
        return model.requests().getFirst().getTools().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(ToolDefinition definition) {
        return (Map<String, Object>) definition.getParameters().get("properties");
    }
}
