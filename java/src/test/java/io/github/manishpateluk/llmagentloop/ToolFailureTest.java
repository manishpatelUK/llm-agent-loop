package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.execution.StepAction;
import io.github.manishpateluk.llmagentloop.execution.StepRecord;
import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolDecision;
import io.github.manishpateluk.llmagentloop.tool.ToolInterceptor;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.complete;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.lastToolResult;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/** A tool handler throwing something unexpected is reported to the model, not a failed run. */
class ToolFailureTest {

    private final ScriptedModel model = new ScriptedModel();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void anUnexpectedToolExceptionBecomesAnErrorResultAndTheRunCompletes() throws InterruptedException {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        List<String> seenByAfter = new CopyOnWriteArrayList<>();
        AgentLoop loop = model.loop().tools(new ToolRegistry().register(definition("explode"), (args, context) -> {
            throw new IllegalStateException("database down");
        })).toolInterceptor(new ToolInterceptor() {
            @Override
            public void failed(ToolCall call, Throwable error, ToolContext context) {
                failed.set(error);
            }

            @Override
            public String after(ToolCall call, String result, ToolContext context) {
                seenByAfter.add(result);
                return result;
            }
        }).build();
        model.respond(request -> lastToolResult(request) == null ? toolCall("e", "explode", Map.of()) : complete("worked around it"));

        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop, LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));

        String expected = "Error: explode failed: IllegalStateException: database down";
        assertThat(capture.error()).isNull();
        assertThat(lastToolResult(model.requests.get(1))).isEqualTo(expected);
        assertThat(capture.result().execution().terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("worked around it");
        assertThat(capture.messages()).anyMatch(m -> m.type() == MessageType.WARNING
                && m.message().equals("Tool explode failed: IllegalStateException: database down"));
        assertThat(capture.result().execution().steps())
                .filteredOn(step -> step.action() == StepAction.TOOL_CALL)
                .extracting(StepRecord::toolName, StepRecord::toolResult)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("explode", expected));
        assertThat(failed.get()).isInstanceOf(IllegalStateException.class).hasMessage("database down");
        assertThat(seenByAfter).containsExactly(expected);
    }

    @Test
    void theModelSeesNoStackTraceAndAMissingMessageFallsBackToTheClassName() {
        AgentLoop loop = model.loop().tools(new ToolRegistry().register(definition("npe"), (args, context) -> {
            throw new NullPointerException();
        })).build();
        model.respond(request -> lastToolResult(request) == null ? toolCall("n", "npe", Map.of()) : complete("ok"));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));

        assertThat(lastToolResult(model.requests.get(1))).isEqualTo("Error: npe failed: NullPointerException");
        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.COMPLETED);
    }

    @Test
    void anExceptionAnInterceptorRaisesStillEndsTheRun() throws InterruptedException {
        AgentLoop loop = model.loop().tools(new ToolRegistry().register(definition("pay"), (args, context) -> "paid"))
                .toolInterceptor(new ToolInterceptor() {
                    @Override
                    public ToolDecision before(ToolCall call, ToolContext context) {
                        throw new SecurityException("payments are disabled");
                    }
                }).build();
        model.respond(request -> toolCall("p", "pay", Map.of()));

        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop, LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));

        assertThat(capture.result()).isNull();
        assertThat(capture.error()).isInstanceOf(SecurityException.class).hasMessage("payments are disabled");
    }

    @Test
    void cancellingWhileAToolThrowsOnInterruptStillCancelsTheRun() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        AgentLoop loop = model.loop().tools(new ToolRegistry().register(definition("slow"), (args, context) -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted", e);
            }
            return "never";
        })).toolInterceptor(new ToolInterceptor() {
            @Override
            public void failed(ToolCall call, Throwable error, ToolContext context) {
                failed.set(error);
            }
        }).build();
        model.respond(request -> toolCall("s", "slow", Map.of()));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AgentLoopResult> result = new AtomicReference<>();
        RunHandle handle = loop.run(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE)
                .onResult(r -> {
                    result.set(r);
                    done.countDown();
                })
                .onError(e -> done.countDown())
                .build());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        handle.cancel();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().execution().terminationReason()).isEqualTo(TerminationReason.CANCELLED);
        assertThat(model.requests).hasSize(1);
        assertThat(failed.get()).isNull();
    }

    @Test
    void aToolThatThrowsWhenTheRunsTimeRunsOutStillEndsTheRunAtItsTimeLimit() {
        AgentLoop loop = model.loop().tools(new ToolRegistry().register(new RegisteredTool(definition("hang"), (args, context) -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted", e);
            }
            return "never";
        }))).build();
        model.respond(request -> toolCall("h", "hang", Map.of()));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE)
                .maxDuration(Duration.ofMillis(300)));

        assertThat(result.execution().terminationReason()).isEqualTo(TerminationReason.TIME_LIMIT_REACHED);
        assertThat(model.requests).hasSize(2); // the working step, then the tools-off wrap-up
        assertThat(model.requests.get(1).getTools()).isEmpty();
    }

    private static ToolDefinition definition(String name) {
        return ToolDefinition.builder().name(name).description(name).parameters(Map.of("type", "object")).build();
    }
}
