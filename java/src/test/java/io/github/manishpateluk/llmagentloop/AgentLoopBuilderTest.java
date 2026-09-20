package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.ToolCall;
import com.manishpateluk.llmrouter.model.ToolDefinition;
import com.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.AgentLoopRunSupport.Capture;
import io.github.manishpateluk.llmagentloop.compression.CompressionMethod;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.manishpateluk.llmagentloop.AgentLoopRunSupport.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopBuilderTest {

    private static final String MODEL = "test-agent-loop-builder-model";

    @AfterEach
    void removeSyntheticModel() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, MODEL);
    }

    @Test
    void adaptersAndRouterTogetherThrows() {
        assertThatThrownBy(() -> AgentLoop.builder()
                .adapters(List.of(new FakeProviderAdapter(r -> Response.builder().content("x").build())))
                .router(new LlmRouter(List.of()))
                .build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void routerAndCompressionMethodsTogetherThrows() {
        assertThatThrownBy(() -> AgentLoop.builder()
                .router(new LlmRouter(List.of()))
                .compressionMethods(List.of(CompressionMethod.STRUCTURAL_COMPACTION))
                .build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void routerPathUsesTheSuppliedRouterAsIs() throws InterruptedException {
        registerModel();
        AtomicBoolean called = new AtomicBoolean();
        LlmRouter router = new LlmRouter(List.of(new FakeProviderAdapter(r -> {
            called.set(true);
            return Response.builder().content("from supplied router").build();
        })));

        AgentLoop loop = AgentLoop.builder().router(router).build();

        Capture capture = run(loop, LoopRequest.builder()
                .prompt("hi")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.NEVER_PLAN).build()));

        assertThat(capture.error()).isNull();
        assertThat(called.get()).isTrue();
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("from supplied router");
    }

    @Test
    void adaptersPathWithDefaultCompressionBuildsAWorkingAgentLoop() throws InterruptedException {
        registerModel();
        AgentLoop loop = AgentLoop.builder()
                .adapters(List.of(new FakeProviderAdapter(r -> Response.builder().content("ok").build())))
                .build();

        Capture capture = run(loop, LoopRequest.builder()
                .prompt("hi")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.NEVER_PLAN).build()));

        assertThat(capture.error()).isNull();
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("ok");
    }

    @Test
    void compressFalseStillBuildsAWorkingAgentLoop() throws InterruptedException {
        registerModel();
        AgentLoop loop = AgentLoop.builder()
                .adapters(List.of(new FakeProviderAdapter(r -> Response.builder().content("ok").build())))
                .compress(false)
                .build();

        Capture capture = run(loop, LoopRequest.builder()
                .prompt("hi")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.NEVER_PLAN).build()));

        assertThat(capture.error()).isNull();
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("ok");
    }

    @Test
    void compressionMethodsWithAutoDetectedAdaptersBuildsWithoutError() {
        // Exercises the one combination HistoryCompressor itself doesn't have a matching overload
        // for (custom methods + auto-detected adapters) — the builder assembles it inline. Never
        // calls run() here: auto-detection reads real environment credentials, and if this dev
        // machine happens to have any set, run() would dispatch a real network call.
        assertThatCode(() -> AgentLoop.builder()
                .compressionMethods(List.of(CompressionMethod.STRUCTURAL_COMPACTION))
                .build())
                .doesNotThrowAnyException();
    }

    @Test
    void toolsAndMemoryAreWiredThroughTheBuilder() throws InterruptedException {
        registerModel();
        ToolRegistry registry = new ToolRegistry();
        registry.register(
                ToolDefinition.builder()
                        .name("echo")
                        .description("Echoes text back")
                        .parameters(Map.of("type", "object"))
                        .build(),
                args -> "echo:" + args.get("text"));

        AtomicBoolean firstCall = new AtomicBoolean(true);
        AgentLoop loop = AgentLoop.builder()
                .adapters(List.of(new FakeProviderAdapter(r -> {
                    if (firstCall.getAndSet(false)) {
                        return Response.builder()
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("1").name("echo").arguments(Map.of("text", "hi")).build()))
                                .content("")
                                .build();
                    }
                    return Response.builder()
                            .toolCalls(List.of(ToolCall.builder()
                                    .id("2").name("report_complete").arguments(Map.of("finalAnswer", "echoed: hi")).build()))
                            .content("")
                            .build();
                })))
                .tools(registry)
                .build();

        Capture capture = run(loop, LoopRequest.builder()
                .prompt("Echo hi")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build()));

        assertThat(capture.error()).isNull();
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("echoed: hi");
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
}
