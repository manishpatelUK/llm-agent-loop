package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.search.Embedder;
import io.github.manishpateluk.llmagentloop.search.SemanticSearch;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.testing.TestRuns;
import io.github.manishpateluk.llmagentloop.testing.TestRuns.TestRun;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.WorkspaceTools;
import io.github.manishpateluk.llmagentloop.usage.UsagePurpose;
import io.github.manishpateluk.llmagentloop.usage.UsageRecord;
import io.github.manishpateluk.llmagentloop.usage.UsageTotals;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.EmbeddingModelEntry;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.EmbeddingResponse;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Costs in micro-dollars: priced embeddings, and many sub-cent calls adding up. */
class UsageCostTest {

    private static final String CHAT = "test-priced-chat";
    private static final String EMBEDDING = "test-priced-embedding";
    private static final Scope ALICE = Scope.of("acme", "alice", "s1");

    @BeforeEach
    void registerPricedModels() {
        // $1 per million input tokens: a 3,000-token call costs $0.003, a third of a cent.
        ModelCapabilityTable.registerModel(ModelEntry.builder().provider(Provider.ANTHROPIC).model(CHAT)
                .contextWindowTokens(100_000).maxOutputTokens(4_000).supportsTools(true)
                .inputCostPerMillionTokens(1.0).outputCostPerMillionTokens(0).build());
        // $0.02 per million tokens, like text-embedding-3-small.
        ModelCapabilityTable.registerEmbeddingModel(EmbeddingModelEntry.builder().provider(Provider.OPENAI).model(EMBEDDING)
                .inputCostPerMillionTokens(0.02).dimensions(2).maxInputTokens(8_000).lastUpdated(Instant.now()).build());
    }

    @AfterEach
    void removePricedModels() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, CHAT);
        ModelCapabilityTable.removeEmbeddingModel(Provider.OPENAI, EMBEDDING);
    }

    /** An embeddings provider reporting {@code tokensPerCall} input tokens for each call. */
    private static ProviderAdapter embeddings(int tokensPerCall) {
        return new ProviderAdapter() {
            @Override
            public Provider id() {
                return Provider.OPENAI;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Response send(String model, Request adaptedRequest) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String defaultEmbeddingModel() {
                return EMBEDDING;
            }

            @Override
            public EmbeddingResponse embed(String model, List<String> texts, Integer dimensions) {
                return EmbeddingResponse.builder()
                        .vectors(texts.stream().map(text -> new float[]{text.length(), 1}).toList())
                        .usage(Usage.builder().inputTokens(tokensPerCall).build()).build();
            }
        };
    }

    @Test
    void routerEmbeddingsCarryLlmRoutersPriceInMicroDollars() {
        Embedder.Embeddings result = Embedder.router(new LlmRouter(List.of(embeddings(50_000)))).embed(List.of("a"));

        // 50,000 tokens at $0.02 per million = $0.001.
        assertThat(result.costUsdMicros()).isEqualTo(1_000);
        assertThat(result.model()).endsWith("/" + EMBEDDING);
    }

    @Test
    void embeddingCostsAreMeteredInMicroDollarsAndCountTowardTheRun() {
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        MockModel model = new MockModel()
                .callTool(WorkspaceTools.WRITE, Map.of("path", "notes.md", "content", "Quarterly numbers."))
                .reply("Saved.");
        AgentLoop loop = model.loopBuilder()
                .tools(new ToolRegistry().registerAll(WorkspaceTools.all()))
                .workspace(new InMemoryWorkspace())
                .usageMeter(records::add)
                .semanticSearch(SemanticSearch.builder()
                        .embedder(Embedder.router(new LlmRouter(List.of(embeddings(50_000))))).build())
                .build();

        TestRun run = TestRuns.run(loop, LoopRequest.builder().prompt("Save my notes").agentProfile(MockModel.STEP_BY_STEP).scope(ALICE));

        UsageRecord embedding = records.stream().filter(r -> r.purpose() == UsagePurpose.EMBEDDING).findFirst().orElseThrow();
        assertThat(embedding.costUsdMicros()).isEqualTo(1_000);
        assertThat(embedding.costUsdCents()).isZero(); // a tenth of a cent rounds to nothing
        assertThat(embedding.executionId()).isEqualTo(run.result().execution().id());
        assertThat(run.result().usage().costUsdMicros()).isGreaterThanOrEqualTo(1_000);
    }

    @Test
    void manySubCentCallsStillReachTheCostLimit() {
        AtomicInteger calls = new AtomicInteger();
        MockModel model = new MockModel().usage(3_000, 0).otherwise(request -> Response.builder().content("")
                .toolCalls(List.of(ToolCall.builder().id("n" + calls.incrementAndGet()).name("noop").arguments(Map.of()).build()))
                .build());
        AgentLoop loop = model.loopBuilder().tools(new ToolRegistry().register(ToolDefinition.builder().name("noop")
                .description("Does nothing").parameters(Map.of("type", "object")).build(), args -> "ok")).build();

        TestRun run = TestRuns.run(loop, LoopRequest.builder().prompt("Keep going").agentProfile(MockModel.STEP_BY_STEP)
                .routerConfig(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, CHAT))).build())
                .maxCostUsdCents(1));

        assertThat(run.error()).isNull();
        assertThat(run.result().execution().terminationReason()).isEqualTo(TerminationReason.COST_LIMIT_REACHED);
        // 0.3 cents a call: the 4th call takes the run past 1 cent, though each call alone rounds to 0 cents.
        assertThat(model.requests()).hasSize(4);
        UsageTotals usage = run.result().usage();
        assertThat(usage.costUsdMicros()).isEqualTo(12_000);
        assertThat(usage.costUsdCents()).isZero();
        assertThat(usage.costUsdCentsRounded()).isEqualTo(1);
    }
}
