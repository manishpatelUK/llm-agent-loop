package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.ToolCall;
import com.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopTest {

    private static final String MODEL = "test-agent-loop-model";

    @AfterEach
    void removeSyntheticModel() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, MODEL);
    }

    @Test
    void constructorRejectsNullRouter() {
        assertThatThrownBy(() -> new AgentLoop(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void canonicalRunRejectsNullRequest() {
        AgentLoop loop = new AgentLoop(new LlmRouter(List.of()));
        assertThatThrownBy(() -> loop.run((LoopRequest) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shortOverloadValidatesPromptBeforeSubmittingAnyWork() {
        AgentLoop loop = new AgentLoop(new LlmRouter(List.of()));
        assertThatThrownBy(() -> loop.run((String) null, r -> { }, e -> { }))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void neverPlanModeMakesExactlyOneCallAndReturnsItsContent() throws InterruptedException {
        registerModel();
        AtomicInteger calls = new AtomicInteger();
        AgentLoop loop = newLoop(request -> {
            calls.incrementAndGet();
            return Response.builder().content("Paris is the capital of France.").build();
        });

        Capture capture = run(loop, LoopRequest.builder()
                .prompt("What is the capital of France?")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.NEVER_PLAN).build()));

        assertThat(capture.error).isNull();
        assertThat(capture.result).isNotNull();
        assertThat(capture.result.finalResponse().getContent()).isEqualTo("Paris is the capital of France.");
        assertThat(capture.result.execution().steps()).hasSize(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void recursiveModeCompletesViaReportCompleteToolCall() throws InterruptedException {
        registerModel();
        AgentLoop loop = newLoop(request -> Response.builder()
                .content("")
                .toolCalls(List.of(ToolCall.builder()
                        .id("1")
                        .name("report_complete")
                        .arguments(Map.of("finalAnswer", "done"))
                        .build()))
                .build());

        Capture capture = run(loop, LoopRequest.builder()
                .prompt("Do a simple thing")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build()));

        assertThat(capture.error).isNull();
        assertThat(capture.result.finalResponse().getContent()).isEqualTo("done");
        assertThat(capture.messages).anyMatch(m -> m.type() == MessageType.INFO && m.message().equals("Goal complete."));
    }

    @Test
    void registeredToolIsCalledAndItsResultFeedsBackIntoTheLoop() throws InterruptedException {
        registerModel();
        AtomicInteger calls = new AtomicInteger();
        AgentLoop loop = newLoop(List.of(), request -> {
            int callNumber = calls.incrementAndGet();
            if (callNumber == 1) {
                return Response.builder()
                        .toolCalls(List.of(ToolCall.builder().id("1").name("echo").arguments(Map.of("text", "hi")).build()))
                        .content("")
                        .build();
            }
            return Response.builder()
                    .toolCalls(List.of(ToolCall.builder()
                            .id("2").name("report_complete").arguments(Map.of("finalAnswer", "echoed: hi")).build()))
                    .content("")
                    .build();
        }, registry -> registry.register(
                com.manishpateluk.llmrouter.model.ToolDefinition.builder()
                        .name("echo")
                        .description("Echoes text back")
                        .parameters(Map.of("type", "object"))
                        .build(),
                args -> "echo:" + args.get("text")));

        Capture capture = run(loop, LoopRequest.builder()
                .prompt("Echo hi")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build()));

        assertThat(capture.error).isNull();
        assertThat(capture.result.finalResponse().getContent()).isEqualTo("echoed: hi");
        assertThat(capture.result.execution().steps())
                .anyMatch(s -> s.toolName() != null && s.toolName().equals("echo") && "echo:hi".equals(s.toolResult()));
        assertThat(capture.messages).anyMatch(m -> m.type() == MessageType.TOOL_CALL);
        assertThat(capture.messages).anyMatch(m -> m.type() == MessageType.TOOL_RESULT);
    }

    @Test
    void unregisteredToolCallEndsTheRunWithAnError() throws InterruptedException {
        registerModel();
        AgentLoop loop = newLoop(request -> Response.builder()
                .content("")
                .toolCalls(List.of(ToolCall.builder().id("1").name("not_registered").arguments(Map.of()).build()))
                .build());

        Capture capture = run(loop, LoopRequest.builder()
                .prompt("Do something")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build()));

        assertThat(capture.result).isNull();
        assertThat(capture.error).isInstanceOf(UnregisteredToolException.class);
    }

    @Test
    void exceedingMaxStepsEndsTheRunWithAnError() throws InterruptedException {
        registerModel();
        AgentLoop loop = newLoop(List.of(), request -> Response.builder()
                .content("")
                .toolCalls(List.of(ToolCall.builder().id("1").name("noop").arguments(Map.of()).build()))
                .build(), registry -> registry.register(
                com.manishpateluk.llmrouter.model.ToolDefinition.builder()
                        .name("noop")
                        .description("Does nothing")
                        .parameters(Map.of("type", "object"))
                        .build(),
                args -> "ok"));

        Capture capture = run(loop, LoopRequest.builder()
                .prompt("Loop forever")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).maxSteps(2).build()));

        assertThat(capture.result).isNull();
        assertThat(capture.error).isInstanceOf(AgentLoopStepLimitExceededException.class);
    }

    private static void registerModel() {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.ANTHROPIC)
                .model(MODEL)
                .contextWindowTokens(100_000)
                .maxOutputTokens(4_000)
                .supportsTools(true)
                .supportsStructuredOutput(true)
                .build());
    }

    private static AgentLoop newLoop(java.util.function.Function<com.manishpateluk.llmrouter.model.Request, Response> responder) {
        return newLoop(List.of(), responder, registry -> { });
    }

    private static AgentLoop newLoop(
            List<Object> unused,
            java.util.function.Function<com.manishpateluk.llmrouter.model.Request, Response> responder,
            java.util.function.Consumer<ToolRegistry> registration) {
        LlmRouter router = new LlmRouter(List.of(new FakeProviderAdapter(responder)));
        ToolRegistry registry = new ToolRegistry();
        registration.accept(registry);
        return new AgentLoop(router, registry);
    }

    private static Capture run(AgentLoop loop, LoopRequest.LoopRequestBuilder builder) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AgentLoopResult> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        List<AgentMessage> messages = new java.util.concurrent.CopyOnWriteArrayList<>();

        loop.run(builder
                .onResult(r -> {
                    resultRef.set(r);
                    latch.countDown();
                })
                .onError(e -> {
                    errorRef.set(e);
                    latch.countDown();
                })
                .onMessage(messages::add)
                .build());

        assertThat(latch.await(5, TimeUnit.SECONDS)).as("run completed within timeout").isTrue();
        return new Capture(resultRef.get(), errorRef.get(), messages);
    }

    private record Capture(AgentLoopResult result, Throwable error, List<AgentMessage> messages) {
    }
}
