package io.github.manishpateluk.llmagentloop.agent;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.skill.Skill;
import io.github.manishpateluk.llmagentloop.skill.SkillLoader;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.workspace.Workspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnDemandSkillsTest {

    private static final Scope ALICE = Scope.of("acme", "alice", "s1");

    private static final Skill EVIDENCE = new Skill("evidence_discipline", "Cite a source for every claim.",
            "EVIDENCE: name the source and date of every figure.", List.of());
    private static final Skill WRITING = new Skill("document_writing", "House style for documents.",
            "WRITING: short sentences, active voice.", List.of());
    private static final RegisteredTool CITE = new RegisteredTool(ToolDefinition.builder().name("cite_source")
            .description("Formats a citation").parameters(Map.of("type", "object")).build(), (args, context) -> "[1]");
    private static final Skill CITATIONS = new Skill("citations", "Formatting citations.", "CITATIONS: use cite_source.",
            List.of(CITE));

    private static Agent agent() {
        return Agent.builder("---\nname: analyst\nplan_mode: recursive\n---\nYou are the analyst.")
                .onDemandSkills(EVIDENCE, WRITING, CITATIONS)
                .build();
    }

    private static Map<String, Object> load(String name) {
        return new HashMap<>(Map.of("name", name));
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePromptCarriesOnlyTheCatalogueAndTheSkillsToolsAreAvailableFromTheStart() {
        MockModel model = new MockModel().reply("Hello.");
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());

        runtime.runAndWait(agent(), "hi", ALICE);

        Request first = model.requests().getFirst();
        assertThat(first.getSystemInstructions()).contains("You are the analyst.",
                "- evidence_discipline: Cite a source for every claim.", "- document_writing: House style for documents.");
        assertThat(first.getSystemInstructions()).doesNotContain("EVIDENCE:", "WRITING:", "CITATIONS:");
        assertThat(MockModel.toolsOffered(first)).contains(SkillLoader.LOAD_SKILL, "cite_source");
        Map<?, ?> nameParameter = (Map<?, ?>) ((Map<?, ?>) first.getTools().stream()
                .filter(t -> t.getName().equals(SkillLoader.LOAD_SKILL)).findFirst().orElseThrow()
                .getParameters().get("properties")).get("name");
        assertThat((List<Object>) nameParameter.get("enum")).containsExactly("evidence_discipline", "document_writing", "citations");
    }

    @Test
    void loadingASkillReturnsItsInstructionsOncePerRunAndUnknownNamesListTheValidOnes() {
        MockModel model = new MockModel()
                .callTool(SkillLoader.LOAD_SKILL, load("evidence_discipline"))
                .callTool(SkillLoader.LOAD_SKILL, load("Evidence_Discipline"))
                .callTool(SkillLoader.LOAD_SKILL, load("pricing"))
                .reply("Done.");
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());

        assertThat(runtime.runAndWait(agent(), "Size the market", ALICE).finalResponse().getContent()).isEqualTo("Done.");

        assertThat(MockModel.lastToolResult(model.requests().get(1)))
                .startsWith("### evidence_discipline").contains("EVIDENCE: name the source");
        assertThat(MockModel.lastToolResult(model.requests().get(2)))
                .contains("already loaded").doesNotContain("EVIDENCE:");
        assertThat(MockModel.lastToolResult(model.requests().get(3)))
                .contains("No skill called 'pricing'; available: evidence_discipline, document_writing, citations");
    }

    @Test
    void eachRunStartsWithNoSkillsLoaded() {
        MockModel model = new MockModel()
                .callTool(SkillLoader.LOAD_SKILL, load("document_writing")).reply("One.")
                .callTool(SkillLoader.LOAD_SKILL, load("document_writing")).reply("Two.");
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());
        Agent agent = agent();

        runtime.runAndWait(agent, "first", ALICE);
        runtime.runAndWait(agent, "second", ALICE);

        assertThat(MockModel.lastToolResult(model.requests().get(1))).contains("WRITING:");
        assertThat(MockModel.lastToolResult(model.requests().get(3))).contains("WRITING:");
    }

    @Test
    void theToolCanBeUsedWithoutAnAgent() {
        RegisteredTool tool = SkillLoader.loadSkill(List.of(EVIDENCE));
        Scope scope = ALICE.atLevel(io.github.manishpateluk.llmagentloop.ScopeLevel.USER);
        ToolContext detached = new ToolContext(UUID.randomUUID(), 0, ALICE, MemoryStore.NONE.scopedTo(scope),
                Workspace.NONE.scopedTo(scope, WorkspaceLimits.DEFAULT));

        assertThat(tool.handler().handle(load("evidence_discipline"), detached)).contains("EVIDENCE:");
        assertThatThrownBy(() -> tool.handler().handle(load("nope"), detached))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("available: evidence_discipline");
        assertThatThrownBy(() -> SkillLoader.loadSkill(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkillLoader.loadSkill(List.of(EVIDENCE, EVIDENCE))).hasMessageContaining("Duplicate");
    }
}
