package io.github.manishpateluk.llmagentloop.agent;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.AgentProfile;
import io.github.manishpateluk.llmagentloop.PlanMode;
import io.github.manishpateluk.llmagentloop.skill.Skill;
import io.github.manishpateluk.llmagentloop.skill.Skills;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTest {

    private static final String DEFINITION = """
            ---
            name: assistant
            description: "A business assistant: admin, finance, product"   # quoted, with a colon
            plan_mode: always_plan
            max_steps: 40
            ---

            # Assistant

            You handle the company's admin end to end. Be concise.
            """;

    @Test
    void readsFrontMatterAndKeepsTheBodyVerbatim() {
        Agent agent = Agent.fromMarkdown(DEFINITION);

        assertThat(agent.name()).isEqualTo("assistant");
        assertThat(agent.description()).isEqualTo("A business assistant: admin, finance, product");
        assertThat(agent.instructions()).isEqualTo("# Assistant\n\nYou handle the company's admin end to end. Be concise.");
        AgentProfile profile = agent.profile();
        assertThat(profile.planMode()).isEqualTo(PlanMode.ALWAYS_PLAN);
        assertThat(profile.maxSteps()).isEqualTo(40);
    }

    @ParameterizedTest
    @CsvSource({"auto, AUTO", "Always-Plan, ALWAYS_PLAN", "never_plan, NEVER_PLAN", "recursive, RECURSIVE_ON_EACH_STEP",
            "recursive_on_each_step, RECURSIVE_ON_EACH_STEP"})
    void planModeSpellings(String written, PlanMode expected) {
        assertThat(Agent.fromMarkdown("---\nname: a\nplan_mode: " + written + "\n---\nbody").profile().planMode()).isEqualTo(expected);
    }

    @Test
    void withoutFrontMatterTheNameComesFromTheBuilder() {
        Agent agent = Agent.builder("Just instructions.").name("helper").description("Helps").build();

        assertThat(agent.instructions()).isEqualTo("Just instructions.");
        assertThat(agent.profile().planMode()).isEqualTo(PlanMode.AUTO);
        assertThat(agent.profile().maxSteps()).isEqualTo(AgentProfile.DEFAULT_MAX_STEPS);
    }

    @Test
    void skillGuidanceIsAppendedAfterTheDefinition() {
        Skill custom = new Skill("Payroll", "UK payroll rules", "Always check the tax year.", List.of());
        Agent agent = Agent.builder(DEFINITION).skills(Skills.spreadsheets(), custom).build();

        String instructions = agent.profile().instructions();
        assertThat(instructions).startsWith("# Assistant").contains("## Skills", "### Spreadsheets", "Use formulas",
                "### Payroll\nUK payroll rules\n\nAlways check the tax year.");
        assertThat(agent.profile().toSystemInstructionsFragment()).startsWith("# Assistant")
                .contains("Agent operating profile: {").doesNotContain("\"instructions\"");
    }

    @Test
    void frontMatterSetsTheAgentsDefaultRouting() {
        Agent agent = Agent.fromMarkdown("""
                ---
                name: drafter
                models: anthropic/claude-sonnet-5-5, OpenAI, openrouter/some/model
                thinking_level: high
                cost_optimized: yes
                ---
                Draft things.
                """);

        RouterConfig config = agent.profile().routerConfig();
        assertThat(config.getRoute()).containsExactly(
                RouteEntry.of(Provider.ANTHROPIC, "claude-sonnet-5-5"),
                RouteEntry.of(Provider.OPENAI),
                RouteEntry.of(Provider.OPENROUTER, "some/model"));
        assertThat(config.getThinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(config.isCostOptimized()).isTrue();
    }

    @Test
    void withoutRoutingKeysTheAgentLeavesRoutingToTheRouter() {
        assertThat(Agent.fromMarkdown("---\nname: a\n---\nx").profile().routerConfig()).isNull();
    }

    @Test
    void anExplicitRouterConfigReplacesFrontMatterRouting() {
        RouterConfig explicit = RouterConfig.builder().thinkingLevel(ThinkingLevel.LOW).build();

        Agent agent = Agent.builder("---\nname: a\nmodels: openai\n---\nx").routerConfig(explicit).build();

        assertThat(agent.profile().routerConfig()).isSameAs(explicit);
        assertThat(agent.routerConfig()).isSameAs(explicit);
    }

    @Test
    void routingMistakesAreReportedClearly() {
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\nmodels: acme-ai/x\n---\nx"))
                .hasMessageContaining("Unknown provider 'acme-ai'");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\nthinking_level: extreme\n---\nx"))
                .hasMessageContaining("thinking_level");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\ncost_optimized: maybe\n---\nx"))
                .hasMessageContaining("cost_optimized");
    }

    @Test
    void definitionMistakesAreReportedClearly() {
        assertThatThrownBy(() -> Agent.fromMarkdown("No name here")).hasMessageContaining("needs a name");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: bad name\n---\nx")).hasMessageContaining("needs a name");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\nmodel: gpt\n---\nx")).hasMessageContaining("Unknown front matter key 'model'");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\ndescription: b\n")).hasMessageContaining("never closed");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\njust some text\n---\nx")).hasMessageContaining("isn't 'key: value'");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\n---\n  ")).hasMessageContaining("no instructions");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\nmax_steps: lots\n---\nx")).hasMessageContaining("max_steps");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\nname: b\n---\nx")).hasMessageContaining("twice");
    }
}
