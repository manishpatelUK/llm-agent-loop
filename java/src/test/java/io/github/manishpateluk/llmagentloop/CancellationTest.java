package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.conversation.InMemoryConversationStore;
import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.complete;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

class CancellationTest {

    private final ScriptedModel model = new ScriptedModel();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void cancellingDuringABlockingToolStopsTheRunGracefully() throws InterruptedException {
        CountDownLatch toolStarted = new CountDownLatch(1);
        AtomicBoolean toolInterrupted = new AtomicBoolean();
        AgentLoop loop = model.loop().tools(new ToolRegistry().register(definition("slow"), (args, context) -> {
            toolStarted.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                toolInterrupted.set(true);
                throw new IllegalStateException("interrupted", e);
            }
            return "never";
        })).build();
        model.respond(request -> toolCall("t1", "slow", Map.of()));

        Result result = start(loop, LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));
        assertThat(toolStarted.await(5, TimeUnit.SECONDS)).isTrue();
        result.handle.cancel();

        assertThat(result.await().execution().terminationReason()).isEqualTo(TerminationReason.CANCELLED);
        assertThat(toolInterrupted).isTrue();
        assertThat(result.handle.isCancelled()).isTrue();
        assertThat(result.handle.isDone()).isTrue();
        assertThat(result.error.get()).isNull();
    }

    @Test
    void cancellingDuringAModelCallStopsBeforeAnyFurtherStep() throws InterruptedException {
        CountDownLatch modelCalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean toolRan = new AtomicBoolean();
        AgentLoop loop = model.loop().tools(new ToolRegistry().register(definition("work"), (args, context) -> {
            toolRan.set(true);
            return "ok";
        })).build();
        model.respond(request -> {
            modelCalled.countDown();
            try {
                release.await(5, TimeUnit.SECONDS); // a model that ignores interrupts, like some HTTP stacks
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return toolCall("t1", "work", Map.of());
        });

        Result result = start(loop, LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));
        assertThat(modelCalled.await(5, TimeUnit.SECONDS)).isTrue();
        result.handle.cancel();
        release.countDown();

        assertThat(result.await().execution().terminationReason()).isEqualTo(TerminationReason.CANCELLED);
        assertThat(toolRan).isFalse();
    }

    @Test
    void cancellingBeforeTheFirstModelCallMakesNoModelCalls() throws InterruptedException {
        AgentLoop loop = model.loop().build();
        CountDownLatch handleReady = new CountDownLatch(1);
        AtomicReference<RunHandle> handle = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AgentLoopResult> result = new AtomicReference<>();

        // The first status update ("Working on: ...") is sent before the first model call.
        handle.set(loop.run(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE)
                .onMessage(message -> {
                    try {
                        handleReady.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    handle.get().cancel();
                })
                .onResult(r -> {
                    result.set(r);
                    done.countDown();
                })
                .onError(e -> done.countDown())
                .build()));
        handleReady.countDown();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().execution().terminationReason()).isEqualTo(TerminationReason.CANCELLED);
        assertThat(result.get().finalResponse().getContent()).isEqualTo("Cancelled before producing a final answer.");
        assertThat(model.requests).isEmpty();
    }

    @Test
    void interruptingARunAndWaitCallerCancelsTheRunAndRestoresTheInterrupt() throws InterruptedException {
        CountDownLatch toolStarted = new CountDownLatch(1);
        AgentLoop loop = model.loop().tools(new ToolRegistry().register(definition("slow"), (args, context) -> {
            toolStarted.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "never";
        })).build();
        model.respond(request -> toolCall("t1", "slow", Map.of()));

        AtomicReference<AgentLoopResult> result = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread caller = Thread.ofVirtual().start(() -> {
            result.set(loop.runAndWait(LoopRequest.builder().prompt("go").agentProfile(RECURSIVE)));
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        assertThat(toolStarted.await(5, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(5_000);

        assertThat(result.get().execution().terminationReason()).isEqualTo(TerminationReason.CANCELLED);
        assertThat(stillInterrupted).isTrue();
    }

    @Test
    void cancellingAFinishedRunDoesNothing() throws InterruptedException {
        AgentLoop loop = model.loop().build();
        Result result = start(loop, LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));
        AgentLoopResult finished = result.await();

        result.handle.cancel();

        assertThat(finished.execution().terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(result.handle.isCancelled()).isFalse();
    }

    @Test
    void aCancelledTurnIsStillSavedToTheConversation() throws InterruptedException {
        CountDownLatch toolStarted = new CountDownLatch(1);
        InMemoryConversationStore store = new InMemoryConversationStore();
        AgentLoop loop = model.loop().conversations(store).tools(new ToolRegistry().register(definition("slow"), (args, context) -> {
            toolStarted.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "never";
        })).build();
        model.respond(request -> toolCall("t1", "slow", Map.of()));
        Scope scope = Scope.of("acme", "alice", "s1");

        Result result = start(loop, LoopRequest.builder().prompt("go").agentProfile(RECURSIVE).scope(scope));
        assertThat(toolStarted.await(5, TimeUnit.SECONDS)).isTrue();
        result.handle.cancel();
        result.await();

        assertThat(store.load(scope)).extracting(m -> m.getContent()).first().isEqualTo("go");
    }

    @Test
    void theHandleCarriesTheExecutionId() throws InterruptedException {
        AgentLoop loop = model.loop().build();
        Result result = start(loop, LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));

        assertThat(result.await().execution().id()).isEqualTo(result.handle.executionId());
    }

    private record Result(RunHandle handle, CountDownLatch done, AtomicReference<AgentLoopResult> result,
                          AtomicReference<Throwable> error) {
        AgentLoopResult await() throws InterruptedException {
            assertThat(done.await(10, TimeUnit.SECONDS)).as("run finished").isTrue();
            return result.get();
        }
    }

    private static Result start(AgentLoop loop, LoopRequest.LoopRequestBuilder builder) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AgentLoopResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        RunHandle handle = loop.run(builder
                .onResult(r -> {
                    result.set(r);
                    done.countDown();
                })
                .onError(e -> {
                    error.set(e);
                    done.countDown();
                })
                .build());
        return new Result(handle, done, result, error);
    }

    private static ToolDefinition definition(String name) {
        return ToolDefinition.builder().name(name).description(name).parameters(Map.of("type", "object")).build();
    }
}
