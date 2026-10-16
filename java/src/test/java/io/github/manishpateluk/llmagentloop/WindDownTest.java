package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.execution.StepAction;
import io.github.manishpateluk.llmagentloop.execution.StepRecord;
import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.AgentDelegate;
import io.github.manishpateluk.llmagentloop.tool.builtin.DelegationTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.text;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs warn the model near a limit, and wrap up with a tools-off answer at it. */
class WindDownTest {

    private final ScriptedModel model = new ScriptedModel();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void atTheStepLimitTheModelIsWarnedOnceThenGivesAToolsOffAnswer() {
        AgentLoop loop = model.loop().tools(digTool()).build();
        model.respond(request -> request.getTools().isEmpty() ? text("Partial findings: found 3 things.") : toolCall("d", "dig", Map.of()));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("Find everything").agentProfile(steps(4)));

        // 4 working steps, then the wrap-up (which doesn't count toward maxSteps).
        assertThat(model.requests).hasSize(5);
        assertThat(model.requests.subList(0, 4)).extracting(WindDownTest::limitWarnings).containsExactly(0L, 0L, 1L, 0L);
        assertThat(warningText(model.requests.get(2))).contains("2 of 4 step(s) left", "give your final answer now");

        Request wrapUp = model.requests.get(4);
        assertThat(wrapUp.getTools()).isEmpty();
        assertThat(wrapUp.getPrompt()).startsWith(AgentLoop.WRAP_UP_PREFIX).contains("step limit (4 steps)", "Find everything");
        // What was gathered comes along as plain text, not tool calls the request couldn't carry.
        assertThat(wrapUp.getHistory()).noneMatch(m -> m.getRole() == Role.TOOL || !m.getToolCalls().isEmpty());
        assertThat(wrapUp.getHistory()).extracting(Message::getContent).anyMatch(c -> c.contains("[Called dig {}]"))
                .anyMatch(c -> c.contains("[Result of dig]\nfound something"));

        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.STEP_LIMIT_REACHED);
        assertThat(result.finalResponse().getContent()).isEqualTo("Partial findings: found 3 things.");
        assertThat(result.execution().steps()).extracting(StepRecord::action).endsWith(StepAction.WRAP_UP);
    }

    @Test
    void aFinalAnswerOnTheLastStepStillCompletes() {
        AgentLoop loop = model.loop().tools(digTool()).build();
        model.respond(request -> model.requests.size() < 3 ? toolCall("d", "dig", Map.of()) : text("All done."));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("Find everything").agentProfile(steps(3)));

        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(result.finalResponse().getContent()).isEqualTo("All done.");
        assertThat(model.requests).hasSize(3);
    }

    @Test
    void warningsCanBeTunedOrTurnedOffForSteps() {
        AgentLoop loop = model.loop().tools(digTool()).build();
        model.respond(request -> request.getTools().isEmpty() ? text("done") : toolCall("d", "dig", Map.of()));

        loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(steps(4)).windDown(WindDown.DEFAULT.withWarnAtStepsLeft(0)));

        assertThat(model.requests).extracting(WindDownTest::limitWarnings).containsOnly(0L);
    }

    @Test
    void nearTheTimeLimitTheModelIsWarnedOnceThenWrapsUp() {
        AgentLoop loop = model.loop().tools(digTool()).build();
        model.respond(request -> {
            if (request.getTools().isEmpty()) {
                return text("Out of time; here's what I have.");
            }
            sleep(80);
            return toolCall("d", "dig", Map.of());
        });

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE)
                .maxDuration(Duration.ofMillis(600)).windDown(WindDown.DEFAULT.withWarnAtTimeFraction(0.5)));

        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.TIME_LIMIT_REACHED);
        assertThat(result.finalResponse().getContent()).isEqualTo("Out of time; here's what I have.");
        assertThat(model.requests).extracting(WindDownTest::limitWarnings).containsOnlyOnce(1L);
        Request warned = model.requests.stream().filter(r -> limitWarnings(r) == 1).findFirst().orElseThrow();
        assertThat(warningText(warned)).contains("of the 600 ms time limit left");
        assertThat(model.requests.getLast().getTools()).isEmpty();
    }

    @Test
    void ifTheWrapUpCallFailsTheRunEndsWithItsLastResponseAndTheSameReason() {
        AgentLoop loop = model.loop().tools(digTool()).build();
        model.respond(request -> {
            if (request.getTools().isEmpty()) {
                throw new IllegalStateException("provider down");
            }
            return ScriptedModel.textAndToolCall("Still digging.", "d", "dig", Map.of());
        });

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(steps(2)));

        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.STEP_LIMIT_REACHED);
        assertThat(result.finalResponse().getContent()).isEqualTo("Still digging.");
        assertThat(result.execution().steps()).extracting(StepRecord::action).endsWith(StepAction.TRUNCATED);
    }

    @Test
    void theWrapUpCallHasItsOwnTimeAllowance() {
        AgentLoop loop = model.loop().tools(digTool()).build();
        model.respond(request -> {
            if (request.getTools().isEmpty()) {
                sleep(30_000);
                return text("too late");
            }
            return ScriptedModel.textAndToolCall("Still digging.", "d", "dig", Map.of());
        });

        long started = System.nanoTime();
        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(steps(1))
                .windDown(WindDown.DEFAULT.withWrapUpTimeout(Duration.ofMillis(200))));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.STEP_LIMIT_REACHED);
        assertThat(result.finalResponse().getContent()).isEqualTo("Still digging.");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    /**
     * A provider client interrupted while it waits (the Anthropic Java client between retries) throws the checked
     * {@link InterruptedException} itself, undeclared. The wrap-up's own watchdog caused it, so the run still ends
     * with its last response rather than failing.
     */
    @Test
    void aWrapUpInterruptedByItsOwnAllowanceEndsTheRunEvenWhenTheClientThrowsInterruptedException() {
        AgentLoop loop = model.loop().tools(digTool()).build();
        model.respond(request -> {
            if (request.getTools().isEmpty()) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    throw WindDownTest.<RuntimeException>sneaky(e);
                }
                return text("too late");
            }
            return ScriptedModel.textAndToolCall("Still digging.", "d", "dig", Map.of());
        });

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(steps(1))
                .windDown(WindDown.DEFAULT.withWrapUpTimeout(Duration.ofMillis(200))));

        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.STEP_LIMIT_REACHED);
        assertThat(result.finalResponse().getContent()).isEqualTo("Still digging.");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    /** Throws a checked exception without declaring it, as Kotlin code can. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }

    @Test
    void aWrappedUpAnswerGetsTheRunsStructuredAnswerToo() {
        AgentLoop loop = model.loop().tools(digTool()).build();
        model.respond(request -> {
            if (request.getResponseSchema() != null) {
                return Response.builder().content("{\"count\": 3}").build();
            }
            return request.getTools().isEmpty() ? text("Found 3.") : toolCall("d", "dig", Map.of());
        });

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("Count them").agentProfile(steps(2))
                .answerSchema(Map.of("type", "object", "properties", Map.of("count", Map.of("type", "integer")))));

        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.STEP_LIMIT_REACHED);
        assertThat(result.structuredAnswer()).containsEntry("count", 3);
    }

    @Test
    void aDelegatedRunCountsTowardTheDelegatingRunsLimitsWhichWindsDownOnceAtTheTop() {
        AgentLoop researcher = model.loop().tools(digTool()).build();
        AgentLoop lead = model.loop().tools(new ToolRegistry().register(DelegationTools.delegateToAgent(List.of(
                AgentDelegate.of("researcher", "Digs things up", researcher, steps(50)))))).build();
        model.respond(request -> {
            List<String> tools = request.getTools().stream().map(ToolDefinition::getName).toList();
            if (tools.contains(DelegationTools.DELEGATE)) {
                return toolCall("r", DelegationTools.DELEGATE, Map.of("agent", "researcher", "task", "Dig deep"));
            }
            if (tools.contains("dig")) {
                return toolCall("d", "dig", Map.of());
            }
            return text("Lead's best answer.");
        });

        AgentLoopResult result = lead.runAndWait(LoopRequest.builder().prompt("Research this").agentProfile(steps(5)));

        // 1 lead step + 4 researcher steps use the lead's 5; then one wrap-up, the lead's.
        assertThat(model.requests).hasSize(6);
        List<Request> wrapUps = model.requests.stream().filter(r -> r.getTools().isEmpty()).toList();
        assertThat(wrapUps).hasSize(1);
        assertThat(wrapUps.getFirst().getPrompt()).contains("step limit (5 steps)", "Research this");
        assertThat(wrapUps.getFirst().getHistory()).extracting(Message::getContent)
                .anyMatch(c -> c.contains("[Called delegate_to_agent"));
        // The researcher's model was warned about the lead's limit, once.
        assertThat(model.requests).extracting(WindDownTest::limitWarnings).containsOnlyOnce(1L);
        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.STEP_LIMIT_REACHED);
        assertThat(result.finalResponse().getContent()).isEqualTo("Lead's best answer.");
    }

    @Test
    void aDelegateReachingItsOwnLimitWrapsUpItselfAndTheDelegatingRunCarriesOn() {
        AgentLoop researcher = model.loop().tools(digTool()).build();
        AgentLoop lead = model.loop().tools(new ToolRegistry().register(DelegationTools.delegateToAgent(List.of(
                AgentDelegate.of("researcher", "Digs things up", researcher, steps(2)))))).build();
        model.respond(request -> {
            List<String> tools = request.getTools().stream().map(ToolDefinition::getName).toList();
            if (tools.contains(DelegationTools.DELEGATE)) {
                return ScriptedModel.lastToolResult(request) == null
                        ? toolCall("r", DelegationTools.DELEGATE, Map.of("agent", "researcher", "task", "Dig deep"))
                        : text("Lead done.");
            }
            return tools.contains("dig") ? toolCall("d", "dig", Map.of()) : text("Researcher's partial findings.");
        });

        AgentLoopResult result = lead.runAndWait(LoopRequest.builder().prompt("Research this").agentProfile(steps(25)));

        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(result.finalResponse().getContent()).isEqualTo("Lead done.");
        assertThat(ScriptedModel.lastToolResult(model.requests.getLast()))
                .contains("STEP_LIMIT_REACHED", "Researcher's partial findings.");
    }

    @Test
    void windDownSettingsAreValidated() {
        assertThatThrownBy(() -> WindDown.DEFAULT.withWarnAtStepsLeft(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindDown.DEFAULT.withWarnAtCostFraction(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindDown.DEFAULT.withWarnAtTimeFraction(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindDown.DEFAULT.withWrapUpTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LoopRequest.builder().prompt("x").onResult(r -> { }).onError(e -> { }).build().windDown())
                .isEqualTo(WindDown.DEFAULT);
    }

    static long limitWarnings(Request request) {
        return request.getHistory().stream()
                .filter(m -> m.getRole() == Role.SYSTEM && m.getContent().startsWith(AgentLoop.LIMIT_WARNING_PREFIX))
                .count();
    }

    private static String warningText(Request request) {
        return request.getHistory().stream()
                .filter(m -> m.getRole() == Role.SYSTEM && m.getContent().startsWith(AgentLoop.LIMIT_WARNING_PREFIX))
                .map(Message::getContent).findFirst().orElseThrow();
    }

    private static AgentProfile steps(int maxSteps) {
        return AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).maxSteps(maxSteps).build();
    }

    private static ToolRegistry digTool() {
        return new ToolRegistry().register(ToolDefinition.builder().name("dig").description("Dig for information")
                .parameters(Map.of("type", "object")).build(), args -> "found something");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
