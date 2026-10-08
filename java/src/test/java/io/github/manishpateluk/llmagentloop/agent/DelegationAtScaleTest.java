package io.github.manishpateluk.llmagentloop.agent;

import io.github.manishpateluk.llmagentloop.AgentLoopResult;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.builtin.AgentDelegate;
import io.github.manishpateluk.llmagentloop.tool.builtin.DelegationTools;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A chat agent in front of 40 specialist delegates. */
class DelegationAtScaleTest {

    private static final int DELEGATES = 40;
    private static final Scope ALICE = Scope.of("acme", "alice", "s1");

    private static String name(int i) {
        return String.format("report_%02d", i);
    }

    private static Agent chatAgent() {
        Agent.Builder chat = Agent.builder("---\nname: chat\nplan_mode: recursive\n---\nYou route work to specialists.");
        for (int i = 1; i <= DELEGATES; i++) {
            chat.delegateTo(Agent.builder("---\nname: " + name(i) + "\ndescription: Writes report number " + i
                    + ".\nplan_mode: recursive\n---\nYou are specialist " + i + ".").build());
        }
        return chat.build();
    }

    private static ToolDefinition delegateTool(MockModel model) {
        return model.requests().getFirst().getTools().stream()
                .filter(t -> t.getName().equals(DelegationTools.DELEGATE)).findFirst().orElseThrow();
    }

    @Test
    void theRightDelegateRunsAndAWrongNameListsTheValidOnes() {
        MockModel model = new MockModel()
                .callTool(DelegationTools.DELEGATE, Map.of("agent", "report_27", "task", "Write report 27"))
                .reply("Report 27 body.")
                .callTool(DelegationTools.DELEGATE, Map.of("agent", "report_99", "task", "Write report 99"))
                .reply("Here is report 27.");
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());

        AgentLoopResult result = runtime.runAndWait(chatAgent(), "I need report 27", ALICE);

        assertThat(result.finalResponse().getContent()).isEqualTo("Here is report 27.");
        assertThat(model.requests().get(1).getSystemInstructions()).startsWith("You are specialist 27.");
        assertThat(model.requests().get(1).getPrompt()).isEqualTo("Write report 27");
        assertThat(MockModel.lastToolResult(model.requests().get(2))).isEqualTo("Report 27 body.");
        assertThat(MockModel.lastToolResult(model.requests().get(3)))
                .contains("No agent called 'report_99'; available: report_01, report_02", "report_40");
        assertThat(model.remaining()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theToolListsEachDelegateOnceCompactlyAndConstrainsTheNameToAnEnum() {
        MockModel model = new MockModel().reply("Hi.");
        AgentRuntime runtime = new AgentRuntime(model.loopBuilder().build());
        runtime.runAndWait(chatAgent(), "hello", ALICE);

        ToolDefinition tool = delegateTool(model);
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= DELEGATES; i++) {
            names.add(name(i));
            String line = "\n- " + name(i) + ": Writes report number " + i + ".";
            assertThat(tool.getDescription()).contains(line);
            assertThat(tool.getDescription().indexOf(line)).isEqualTo(tool.getDescription().lastIndexOf(line));
        }
        Map<?, ?> agentParameter = (Map<?, ?>) ((Map<?, ?>) tool.getParameters().get("properties")).get("agent");
        assertThat((List<Object>) agentParameter.get("enum")).containsExactlyElementsOf(names);
        // Fixed preamble plus one short line per delegate.
        assertThat(tool.getDescription().length()).isLessThan(250 + DELEGATES * 40);
    }

    @Test
    void longOrMultiLineDescriptionsAreCutToOneShortLine() {
        AgentDelegate verbose = new AgentDelegate() {
            @Override
            public String name() {
                return "verbose";
            }

            @Override
            public String description() {
                return "Writes go-to-market plans covering pricing, channels, positioning, launch sequencing, partner "
                        + "programmes, sales enablement and every other aspect you can think of in great detail.\n"
                        + "Second line with more detail that shouldn't appear.";
            }

            @Override
            public String run(String task, ToolContext context) {
                return "";
            }
        };

        String description = DelegationTools.delegateToAgent(List.of(verbose)).definition().getDescription();
        String line = description.substring(description.indexOf("\n- verbose: ") + 1);

        assertThat(line).startsWith("- verbose: Writes go-to-market plans").endsWith("…").doesNotContain("Second line");
        assertThat(line.length()).isLessThanOrEqualTo("- verbose: ".length() + DelegationTools.MAX_SUMMARY_CHARS + 1);

    }
}
