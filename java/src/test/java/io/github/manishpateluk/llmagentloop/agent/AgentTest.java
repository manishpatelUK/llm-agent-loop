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
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\ndescription: b\n")).hasMessageContaining("never closed");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\njust some text\n---\nx")).hasMessageContaining("isn't 'key: value'");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\n---\n  ")).hasMessageContaining("no instructions");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\nmax_steps: lots\n---\nx")).hasMessageContaining("max_steps");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\nname: b\n---\nx")).hasMessageContaining("twice");
    }

    @Test
    void unknownFrontMatterKeysAreKeptInOrderAsTheApplicationsMetadata() {
        Agent agent = Agent.fromMarkdown("""
                ---
                name: competitive_landscape
                description: Competitive Landscape - direct and indirect competitors.
                skills: evidence_discipline, document_writing
                knowledge: competition_framework
                silos: gtm, resilience
                produces: "competitive_landscape"
                plan_mode: recursive
                ---
                Map the competition.
                """);

        assertThat(agent.metadata()).containsExactly(
                java.util.Map.entry("skills", "evidence_discipline, document_writing"),
                java.util.Map.entry("knowledge", "competition_framework"),
                java.util.Map.entry("silos", "gtm, resilience"),
                java.util.Map.entry("produces", "competitive_landscape"));
        assertThat(agent.profile().planMode()).isEqualTo(PlanMode.RECURSIVE_ON_EACH_STEP);
        assertThat(agent.profile().instructions()).isEqualTo("Map the competition.");
        assertThatThrownBy(() -> agent.metadata().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(Agent.fromMarkdown(DEFINITION).metadata()).isEmpty();
    }

    @Test
    void knownKeysAreStillValidatedWhenUnknownOnesAreKept() {
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\nsilos: gtm\nplan_mode: sometimes\n---\nx"))
                .hasMessageContaining("plan_mode must be one of");
        assertThatThrownBy(() -> Agent.fromMarkdown("---\nname: a\nsilos: gtm\nmax_steps: lots\n---\nx"))
                .hasMessageContaining("max_steps");
    }

    @Test
    void strictFrontMatterRejectsUnknownKeysToCatchMisspellings() {
        assertThatThrownBy(() -> Agent.builder("---\nname: a\nplan-mode: always_plan\n---\nx").strictFrontMatter().build())
                .hasMessageContaining("Unknown front matter key 'plan-mode'").hasMessageContaining("plan_mode");
        assertThat(Agent.builder("---\nname: a\nplan_mode: always_plan\n---\nx").strictFrontMatter().build().name())
                .isEqualTo("a");
    }

    @Test
    void onDemandSkillsAppearInThePromptAsACatalogueOnly() {
        Agent agent = Agent.builder("---\nname: a\n---\nDo the work.")
                .skill(new Skill("inline_rules", "Always on", "Inline rules text.", List.of()))
                .onDemandSkills(new Skill("evidence_discipline", "Cite a source for every claim.\nMore detail here.",
                        "Long evidence instructions.", List.of()))
                .build();

        String instructions = agent.profile().instructions();
        assertThat(instructions).contains("Inline rules text.", "## Skills you can load", "load_skill",
                "- evidence_discipline: Cite a source for every claim.");
        assertThat(instructions).doesNotContain("Long evidence instructions.", "More detail here.");
        assertThat(agent.onDemandSkills()).extracting(Skill::name).containsExactly("evidence_discipline");
    }

    @Test
    void skillNamesMustBeUniqueAcrossInlinedAndOnDemandSkills() {
        Skill rules = new Skill("Rules", "", "x", List.of());
        assertThatThrownBy(() -> Agent.builder("---\nname: a\n---\nx").skill(rules)
                .onDemandSkill(new Skill("rules", "", "y", List.of())).build())
                .hasMessageContaining("more than one skill called 'rules'");
        assertThatThrownBy(() -> Agent.builder("---\nname: a\n---\nx").onDemandSkills(rules, rules).build())
                .hasMessageContaining("more than one skill");
    }
}
