package io.github.manishpateluk.llmagentloop.agent;

import io.github.manishpateluk.llmagentloop.AgentProfile;
import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.PlanMode;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.tool.builtin.DelegationTools;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.provider.Provider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-run operating context: the user's live context, given per turn while the agent definition stays fixed. */
class OperatingContextTest {

    private static final Scope ALICE = Scope.of("acme", "alice", "s1");
    private static final String CONTEXT = "User: Alice Smith, founder of Acme Ltd.\nOn file: pitch deck v3, Q3 accounts.";
    private static final String HEADING = "## Operating context for this run";

    private static Agent research() {
        return Agent.builder("---\nname: research\ndescription: Market research\nplan_mode: recursive\n---\nYou research markets.").build();
    }

    private static Agent chat(Agent... delegates) {
        Agent.Builder builder = Agent.builder("---\nname: chat\nplan_mode: recursive\nmax_steps: 7\n---\nYou are the chat agent.");
        for (Agent delegate : delegates) {
            builder.delegateTo(delegate);
        }
        return builder.build();
    }

    private static MockModel delegatingModel() {
        return new MockModel()
                .callTool(DelegationTools.DELEGATE, Map.of("agent", "research", "task", "Size the UK market"))
                .reply("About 2m firms.")
                .reply("The UK market is about 2m firms.");
    }

    @Test
    void theRunsContextReachesItsSystemPromptAfterTheAgentsOwnInstructions() {
        MockModel model = new MockModel().reply("Hi Alice.");
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());

        runtime.runAndWait(chat(), LoopRequest.builder().prompt("hello").scope(ALICE).operatingContext(CONTEXT)
                // The agent's own profile wins over one set on the request.
                .agentProfile(AgentProfile.builder().instructions("IGNORED").planMode(PlanMode.ALWAYS_PLAN).build()));

        String system = model.requests().getFirst().getSystemInstructions();
        assertThat(system).startsWith("You are the chat agent.").doesNotContain("IGNORED")
                .contains(HEADING + "\n" + CONTEXT);
        assertThat(system.indexOf(HEADING)).isGreaterThan(system.indexOf("Agent operating profile:"));
        assertThat(model.requests()).hasSize(1); // still the agent's recursive plan mode: no planning calls
    }

    @Test
    void eachTurnCanCarryDifferentContextWhileTheAgentStaysTheSame() {
        MockModel model = new MockModel().reply("One.").reply("Two.").reply("Three.");
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());
        Agent agent = chat();

        runtime.runAndWait(agent, LoopRequest.builder().prompt("a").scope(ALICE).operatingContext("Plan: free"));
        runtime.runAndWait(agent, LoopRequest.builder().prompt("b").scope(ALICE).operatingContext("Plan: paid"));
        runtime.runAndWait(agent, LoopRequest.builder().prompt("c").scope(ALICE));

        assertThat(model.requests().get(0).getSystemInstructions()).contains("Plan: free").doesNotContain("Plan: paid");
        assertThat(model.requests().get(1).getSystemInstructions()).contains("Plan: paid").doesNotContain("Plan: free");
        assertThat(model.requests().get(2).getSystemInstructions()).doesNotContain(HEADING);
    }

    @Test
    void withoutContextThereIsNoContextSection() {
        MockModel model = new MockModel().reply("Hi.");
        new AgentRuntime(model.loopBuilder().build()).runAndWait(chat(), LoopRequest.builder().prompt("hi").scope(ALICE)
                .operatingContext("   "));

        assertThat(model.requests().getFirst().getSystemInstructions()).doesNotContain(HEADING);
    }

    @Test
    void aDelegatedRunInheritsTheContextByDefault() {
        MockModel model = delegatingModel();
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());

        runtime.runAndWait(chat(research()), LoopRequest.builder().prompt("How big is the UK market?").scope(ALICE)
                .operatingContext(CONTEXT));

        Request delegated = model.requests().get(1);
        assertThat(delegated.getSystemInstructions()).startsWith("You research markets.").contains(HEADING + "\n" + CONTEXT);
        assertThat(delegated.getPrompt()).isEqualTo("Size the UK market");
    }

    @Test
    void aDelegateCanOptOutOfInheritingTheContext() {
        MockModel model = delegatingModel();
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());
        Agent chat = Agent.builder("---\nname: chat\nplan_mode: recursive\n---\nYou are the chat agent.")
                .delegateTo(runtime.delegate(research(), false))
                .build();

        runtime.runAndWait(chat, LoopRequest.builder().prompt("How big is the UK market?").scope(ALICE).operatingContext(CONTEXT));

        assertThat(model.requests().getFirst().getSystemInstructions()).contains(CONTEXT);
        assertThat(model.requests().get(1).getSystemInstructions()).startsWith("You research markets.")
                .doesNotContain(HEADING).doesNotContain("Alice");
    }

    @Test
    void routerConfigStillOverridesPerRequest() {
        MockModel model = new MockModel().reply("Hi.");
        RouterConfig pinned = RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC))).build();

        new AgentRuntime(model.loopBuilder().build()).runAndWait(chat(), LoopRequest.builder().prompt("hi").scope(ALICE)
                .operatingContext(CONTEXT).routerConfig(pinned));

        assertThat(model.requests().getFirst().getConfig().getRoute()).isEqualTo(pinned.getRoute());
    }
}
