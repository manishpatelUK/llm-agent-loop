package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.compression.HistoryCompressor;
import io.github.manishpateluk.llmagentloop.compression.CompressionMethod;
import io.github.manishpateluk.llmagentloop.usage.InMemoryUsageMeter;
import io.github.manishpateluk.llmagentloop.usage.UsageMeter;
import io.github.manishpateluk.llmagentloop.usage.UsagePurpose;
import io.github.manishpateluk.llmagentloop.usage.UsageRecord;
import io.github.manishpateluk.llmagentloop.usage.UsageTotals;
import io.github.manishpateluk.llmrouter.LlmRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.text;
import static org.assertj.core.api.Assertions.assertThat;

class UsageMeteringTest {

    private static final Scope ALICE = Scope.of("acme", "alice", "s1");
    private static final Scope BOB = Scope.of("acme", "bob", "s1");

    private final ScriptedModel model = new ScriptedModel();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void meteringIsOnByDefaultWithTotalsPerTenantAndUser() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> withUsage(text("ok"), 100, 20));

        loop.runAndWait(LoopRequest.builder().prompt("a").scope(ALICE).agentProfile(RECURSIVE));
        loop.runAndWait(LoopRequest.builder().prompt("b").scope(ALICE).agentProfile(RECURSIVE));
        loop.runAndWait(LoopRequest.builder().prompt("c").scope(BOB).agentProfile(RECURSIVE));

        InMemoryUsageMeter meter = (InMemoryUsageMeter) loop.usageMeter();
        assertThat(meter.totals(ALICE.atLevel(ScopeLevel.USER))).isEqualTo(new UsageTotals(2, 200, 40, totalsCost(meter, ALICE),
                meter.totals(ALICE.atLevel(ScopeLevel.USER)).costUsdMicros()));
        assertThat(meter.totals(ALICE)).isEqualTo(meter.totals(ALICE.atLevel(ScopeLevel.USER)));
        assertThat(meter.totals(ALICE.atLevel(ScopeLevel.TENANT)).calls()).isEqualTo(3);
        assertThat(meter.totals(BOB).inputTokens()).isEqualTo(100);
        meter.reset();
        assertThat(meter.totals(ALICE)).isEqualTo(UsageTotals.ZERO);
    }

    @Test
    void eachRunReportsItsOwnUsage() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> withUsage(text("ok"), 7, 3));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("a").agentProfile(RECURSIVE));

        assertThat(result.usage().calls()).isEqualTo(1);
        assertThat(result.usage().inputTokens()).isEqualTo(7);
        assertThat(result.usage().outputTokens()).isEqualTo(3);
        assertThat(result.usage().totalTokens()).isEqualTo(10);
    }

    @Test
    void aCustomMeterSeesEveryCallWithItsPurposeScopeAndModel() {
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        AgentLoop loop = model.loop().usageMeter(records::add).build();
        model.respond(request -> request.getPrompt().contains("Does accomplishing this require")
                ? withUsage(Response.builder().content("{\"needsPlan\":false,\"reason\":\"simple\"}").build(), 5, 1)
                : withUsage(text("done"), 50, 10));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("go").scope(ALICE)
                .agentProfile(AgentProfile.builder().planMode(PlanMode.AUTO).build()));

        assertThat(records).extracting(UsageRecord::purpose).containsExactly(UsagePurpose.PLAN_CHECK, UsagePurpose.STEP);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.scope()).isEqualTo(ALICE);
            assertThat(r.executionId()).isEqualTo(result.execution().id());
            assertThat(r.provider()).isEqualTo(Provider.ANTHROPIC);
            assertThat(r.model()).isNotBlank();
        });
        assertThat(result.usage().inputTokens()).isEqualTo(55);
    }

    @Test
    void meteringCanBeSwitchedOffWhileRunsStillReportTheirUsage() {
        AgentLoop loop = model.loop().usageMeter(UsageMeter.NONE).build();
        model.respond(request -> withUsage(text("ok"), 9, 1));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("a").agentProfile(RECURSIVE));

        assertThat(loop.usageMeter()).isSameAs(UsageMeter.NONE);
        assertThat(result.usage().inputTokens()).isEqualTo(9);
    }

    @Test
    void historyCompressionsOwnModelCallsAreMetered() {
        String tiny = "test-metering-tiny-model";
        ModelCapabilityTable.registerModel(ModelEntry.builder().provider(Provider.ANTHROPIC).model(tiny)
                .contextWindowTokens(700).maxOutputTokens(0).supportsTools(true).build());
        try {
            List<UsageRecord> records = new CopyOnWriteArrayList<>();
            AtomicReference<LlmRouter> self = new AtomicReference<>();
            LlmRouter router = new LlmRouter(List.of(new FakeProviderAdapter(request -> request.getPrompt().startsWith("Summarize")
                    ? withUsage(text("summary"), 40, 4)
                    : withUsage(text("answer"), 10, 2))),
                    // The summarization request itself is left alone, as a real router with a roomier model would.
                    (provider, m, request) -> request.getPrompt().startsWith("Summarize") ? request
                            : HistoryCompressor.compress(request, Provider.ANTHROPIC, tiny,
                                    List.of(CompressionMethod.LLM_SUMMARIZATION), self.get()).request());
            self.set(router);
            AgentLoop loop = AgentLoop.builder().router(router).usageMeter(records::add).build();
            List<io.github.manishpateluk.llmrouter.model.Message> longHistory = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                longHistory.add(io.github.manishpateluk.llmrouter.model.Message.user("question " + i + " " + "x".repeat(80)));
                longHistory.add(io.github.manishpateluk.llmrouter.model.Message.assistant("answer " + i + " " + "y".repeat(80)));
            }

            loop.runAndWait(LoopRequest.builder().prompt("next").history(longHistory).agentProfile(RECURSIVE)
                    .routerConfig(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, tiny))).build()));

            assertThat(records).extracting(UsageRecord::purpose).contains(UsagePurpose.HISTORY_COMPRESSION, UsagePurpose.STEP);
            assertThat(records.stream().filter(r -> r.purpose() == UsagePurpose.HISTORY_COMPRESSION))
                    .allSatisfy(r -> assertThat(r.inputTokens()).isEqualTo(40));
        } finally {
            ModelCapabilityTable.removeModel(Provider.ANTHROPIC, tiny);
        }
    }

    private static long totalsCost(InMemoryUsageMeter meter, Scope scope) {
        return meter.totals(scope.atLevel(ScopeLevel.USER)).costUsdCents();
    }

    private static Response withUsage(Response response, int input, int output) {
        return response.toBuilder().usage(Usage.builder().inputTokens(input).outputTokens(output).build()).build();
    }
}
