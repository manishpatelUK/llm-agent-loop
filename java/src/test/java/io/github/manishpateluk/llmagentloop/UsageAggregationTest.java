package io.github.manishpateluk.llmagentloop;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import io.github.manishpateluk.llmagentloop.compression.CompressionMethod;
import io.github.manishpateluk.llmagentloop.compression.HistoryCompressor;
import io.github.manishpateluk.llmagentloop.conversation.ConversationCompaction;
import io.github.manishpateluk.llmagentloop.conversation.InMemoryConversationStore;
import io.github.manishpateluk.llmagentloop.memory.InMemoryMemoryStore;
import io.github.manishpateluk.llmagentloop.search.Embedder;
import io.github.manishpateluk.llmagentloop.search.SemanticSearch;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.AgentDelegate;
import io.github.manishpateluk.llmagentloop.tool.builtin.DelegationTools;
import io.github.manishpateluk.llmagentloop.tool.builtin.MemoryTools;
import io.github.manishpateluk.llmagentloop.usage.InMemoryUsageMeter;
import io.github.manishpateluk.llmagentloop.usage.UsagePurpose;
import io.github.manishpateluk.llmagentloop.usage.UsageRecord;
import io.github.manishpateluk.llmagentloop.usage.UsageTotals;
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nothing a provider reports about a call is lost on its way into {@link UsageRecord}s, run totals and meter
 * totals: every token count (cache reads and writes, long-lived writes, reasoning) and the cost, on every kind of
 * call, a run's delegated runs included.
 */
class UsageAggregationTest {

    private static final Scope ALICE = Scope.of("acme", "alice", "s1");
    private static final Map<String, String> TAGS = Map.of("company", "c1", "origin", "test");
    private static final String PRICED = "test-aggregation-priced";
    private static final String TINY = "test-aggregation-tiny";
    private static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("city", Map.of("type", "string")), "required", List.of("city"));

    /** What every scripted chat call reports: distinct numbers, so a field copied into the wrong place shows. */
    private static final Usage REPORTED = Usage.builder().inputTokens(10_000).outputTokens(700).cacheReadTokens(6_000)
            .cacheWriteTokens(3_000).cacheWriteLongTtlTokens(1_000).reasoningTokens(200).build();

    private final ScriptedModel model = new ScriptedModel();

    @BeforeEach
    void priceModels() {
        // $3 in, $15 out, reads $0.30, writes $3.75, hour-long writes $6 per million: every token class costs something.
        ModelCapabilityTable.registerModel(ModelEntry.builder().provider(Provider.ANTHROPIC).model(PRICED)
                .contextWindowTokens(200_000).maxOutputTokens(8_000).supportsTools(true).supportsStructuredOutput(true)
                .inputCostPerMillionTokens(3).outputCostPerMillionTokens(15).cacheReadCostPerMillionTokens(0.3)
                .cacheWriteCostPerMillionTokens(3.75).cacheWriteLongTtlCostPerMillionTokens(6.0).build());
        ModelCapabilityTable.registerModel(ModelEntry.builder().provider(Provider.ANTHROPIC).model(TINY)
                .contextWindowTokens(700).maxOutputTokens(0).supportsTools(true).build());
    }

    @AfterEach
    void cleanUp() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, PRICED);
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, TINY);
        model.close();
    }

    private static RouterConfig priced() {
        return RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, PRICED))).build();
    }

    private static Response reported(Response response) {
        return response.toBuilder().usage(REPORTED).build();
    }

    // ---- every field of llm-router's Usage reaches the record --------------------------------------------------

    /**
     * Fills every numeric field of {@link Usage} with a distinct value and checks {@link UsageRecord#of} copies each
     * one. Found by reflection, so a field llm-router adds later fails this test until the record carries it.
     */
    @Test
    void everyUsageFieldReachesTheRecord() throws Exception {
        Map<String, String> recordFieldFor = Map.of(
                "inputTokens", "inputTokens", "outputTokens", "outputTokens", "reasoningTokens", "reasoningTokens",
                "cacheReadTokens", "cacheReadTokens", "cacheWriteTokens", "cacheWriteTokens",
                "cacheWriteLongTtlTokens", "cacheWriteLongTtlTokens",
                "estimatedCostUsdCents", "costUsdCents", "estimatedCostUsdMicros", "costUsdMicros");
        Usage.UsageBuilder builder = Usage.builder();
        Map<String, Long> values = new HashMap<>();
        long next = 11;
        for (Field field : Usage.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertThat(recordFieldFor).as("UsageRecord must carry Usage." + field.getName()).containsKey(field.getName());
            long value = next;
            next += 13;
            values.put(field.getName(), value);
            var setter = Usage.UsageBuilder.class.getMethod(field.getName(), field.getType());
            setter.invoke(builder, field.getType() == int.class ? (Object) (int) value : (Object) value);
        }
        Response response = Response.builder().content("x").usage(builder.build())
                .providerUsed(Provider.OPENAI).modelUsed("m").build();
        UUID execution = UUID.randomUUID();

        UsageRecord record = UsageRecord.of(execution, ALICE, UsagePurpose.STEP, response, TAGS);

        for (var entry : values.entrySet()) {
            Object copied = UsageRecord.class.getMethod(recordFieldFor.get(entry.getKey())).invoke(record);
            assertThat(((Number) copied).longValue()).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        assertThat(record.executionId()).isEqualTo(execution);
        assertThat(record.scope()).isEqualTo(ALICE);
        assertThat(record.provider()).isEqualTo(Provider.OPENAI);
        assertThat(record.model()).isEqualTo("m");
        assertThat(record.tags()).isEqualTo(TAGS);
    }

    @Test
    void aResponseWithoutUsageMakesAnAllZeroRecord() {
        UsageRecord record = UsageRecord.of(null, ALICE, UsagePurpose.STEP, Response.builder().content("x").build(), null);

        assertThat(UsageTotals.ZERO.plus(record)).isEqualTo(new UsageTotals(1, 0, 0, 0, 0));
        assertThat(record.tags()).isEmpty();
    }

    // ---- totals sum every field ---------------------------------------------------------------------------------

    /**
     * Every numeric component of {@link UsageRecord} has a {@link UsageTotals} component of the same name that sums
     * it — checked by reflection over random records, both ways of adding — so a field added to one and not the
     * other fails here.
     */
    @Test
    void totalsSumEveryNumericFieldOfTheRecords() throws Exception {
        List<String> recordFields = Arrays.stream(UsageRecord.class.getRecordComponents())
                .filter(c -> c.getType() == long.class).map(RecordComponent::getName).toList();
        List<String> totalFields = Arrays.stream(UsageTotals.class.getRecordComponents())
                .map(RecordComponent::getName).filter(name -> !name.equals("calls")).toList();
        assertThat(totalFields).containsExactlyInAnyOrderElementsOf(recordFields);

        Random random = new Random(42);
        List<UsageRecord> records = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            records.add(new UsageRecord(null, ALICE, UsagePurpose.values()[i % UsagePurpose.values().length], null, null,
                    random.nextInt(1_000_000), random.nextInt(50_000), random.nextInt(100), random.nextInt(1_000_000),
                    Instant.now(), Map.of(), random.nextInt(500_000), random.nextInt(200_000), random.nextInt(50_000),
                    random.nextInt(20_000)));
        }
        UsageTotals oneByOne = UsageTotals.ZERO;
        for (UsageRecord record : records) {
            oneByOne = oneByOne.plus(record);
        }
        UsageTotals firstHalf = records.subList(0, 100).stream().reduce(UsageTotals.ZERO, UsageTotals::plus, UsageTotals::plus);
        UsageTotals secondHalf = records.subList(100, 200).stream().reduce(UsageTotals.ZERO, UsageTotals::plus, UsageTotals::plus);

        assertThat(firstHalf.plus(secondHalf)).isEqualTo(oneByOne);
        assertThat(oneByOne.plus(UsageTotals.ZERO)).isEqualTo(oneByOne);
        assertThat(oneByOne.calls()).isEqualTo(200);
        for (String field : recordFields) {
            long sum = 0;
            for (UsageRecord record : records) {
                sum += (long) UsageRecord.class.getMethod(field).invoke(record);
            }
            assertThat((long) UsageTotals.class.getMethod(field).invoke(oneByOne)).as(field).isEqualTo(sum);
        }
    }

    @Test
    void theOlderConstructorsMeanNoCachedTokens() {
        UsageRecord old = new UsageRecord(null, ALICE, UsagePurpose.STEP, null, null, 100, 10, 1, 9_000, Instant.EPOCH, TAGS);

        assertThat(old).isEqualTo(new UsageRecord(null, ALICE, UsagePurpose.STEP, null, null, 100, 10, 1, 9_000, Instant.EPOCH,
                TAGS, 0, 0, 0, 0));
        assertThat(old.uncachedInputTokens()).isEqualTo(100);
        assertThat(new UsageTotals(2, 100, 10, 1, 9_000)).isEqualTo(new UsageTotals(2, 100, 10, 1, 9_000, 0, 0, 0, 0));
    }

    @Test
    void theDerivedFiguresNeverGoNegative() {
        UsageTotals totals = new UsageTotals(1, 1_000, 0, 0, 0, 900, 300, 0, 0);

        assertThat(totals.uncachedInputTokens()).isZero();
        assertThat(totals.cacheReadFraction()).isEqualTo(0.9);
        assertThat(UsageTotals.ZERO.cacheReadFraction()).isZero();
    }

    // ---- every kind of call, end to end -------------------------------------------------------------------------

    /**
     * Runs that between them make every kind of metered call, each reporting {@link #REPORTED}; every record
     * carries all its numbers and the run's tags, and every {@link UsagePurpose} is covered — so a new purpose fails
     * this test until it's metered here too.
     */
    @Test
    void everyKindOfCallIsMeteredWithAllItsNumbers() {
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        List<AgentLoopResult> results = new CopyOnWriteArrayList<>();

        // Planning, working steps, answer formatting and (on the second turn) conversation compaction.
        AgentLoop planner = model.loop().usageMeter(records::add).conversations(new InMemoryConversationStore())
                .conversationCompaction(ConversationCompaction.summarizing(2, 1)).build();
        model.respond(request -> {
            if (request.getPrompt().contains("Does accomplishing this require")) {
                return reported(Response.builder().content("{\"needsPlan\":true,\"reason\":\"two parts\"}").build());
            }
            if (request.getPrompt().contains("ordered list of steps")) {
                return reported(Response.builder().content("{\"summary\":\"s\",\"steps\":[{\"description\":\"find it\"}]}").build());
            }
            if (request.getResponseSchema() != null || request.getPrompt().startsWith("Express the answer")) {
                return reported(Response.builder().content("{\"city\":\"Paris\"}").build());
            }
            return reported(text("Paris."));
        });
        for (String prompt : List.of("Capital of France?", "And of Spain?")) {
            results.add(planner.runAndWait(LoopRequest.builder().prompt(prompt).scope(ALICE).tags(TAGS).routerConfig(priced())
                    .agentProfile(AgentProfile.builder().planMode(PlanMode.AUTO).build()).answerSchema(SCHEMA)));
        }

        // A wrap-up, at the step limit.
        AgentLoop digger = model.loop().usageMeter(records::add).tools(new ToolRegistry().register(ToolDefinition.builder()
                .name("dig").description("Digs").parameters(Map.of("type", "object")).build(), args -> "found")).build();
        model.respond(request -> reported(request.getTools().isEmpty() ? text("Best so far.")
                : ScriptedModel.toolCall("d", "dig", Map.of())));
        results.add(digger.runAndWait(LoopRequest.builder().prompt("Dig").scope(ALICE).tags(TAGS).routerConfig(priced())
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).maxSteps(1).build())));

        // History compression's own model call.
        AtomicReference<LlmRouter> self = new AtomicReference<>();
        LlmRouter compressing = new LlmRouter(List.of(new FakeProviderAdapter(request -> reported(
                request.getPrompt().startsWith("Summarize") ? text("summary") : text("answer")))),
                (provider, m, request) -> request.getPrompt().startsWith("Summarize") ? request
                        : HistoryCompressor.compress(request, Provider.ANTHROPIC, TINY,
                                List.of(CompressionMethod.LLM_SUMMARIZATION), self.get()).request());
        self.set(compressing);
        List<Message> longHistory = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            longHistory.add(Message.user("question " + i + " " + "x".repeat(80)));
            longHistory.add(Message.assistant("answer " + i + " " + "y".repeat(80)));
        }
        results.add(AgentLoop.builder().router(compressing).usageMeter(records::add).build().runAndWait(LoopRequest.builder()
                .prompt("next").history(longHistory).scope(ALICE).tags(TAGS).agentProfile(RECURSIVE)
                .routerConfig(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, TINY))).build())));

        // Embeddings, for a memory saved with semantic search on.
        Embedder embedder = texts -> new Embedder.Embeddings(texts.stream().map(t -> new float[] {t.length(), 1}).toList(),
                "test-embedding", Provider.OPENAI, 25L * texts.size(), 5L * texts.size());
        AgentLoop remembering = model.loop().usageMeter(records::add).memory(new InMemoryMemoryStore())
                .semanticSearch(SemanticSearch.builder().embedder(embedder).build())
                .tools(new ToolRegistry().registerAll(MemoryTools.all())).build();
        model.respond(request -> reported(ScriptedModel.lastToolResult(request) == null
                ? ScriptedModel.toolCall("m", MemoryTools.SAVE, Map.of("content", "Alice likes tables."))
                : text("Noted.")));
        results.add(remembering.runAndWait(LoopRequest.builder().prompt("Remember this").scope(ALICE).tags(TAGS)
                .routerConfig(priced()).agentProfile(RECURSIVE)));

        assertThat(records.stream().map(UsageRecord::purpose).collect(Collectors.toCollection(() -> EnumSet.noneOf(UsagePurpose.class))))
                .as("every purpose metered").isEqualTo(EnumSet.allOf(UsagePurpose.class));
        assertThat(records).allSatisfy(record -> assertThat(record.tags()).as(record.purpose().name()).isEqualTo(TAGS));
        assertThat(records.stream().filter(r -> r.purpose() != UsagePurpose.EMBEDDING)).allSatisfy(record -> {
            assertThat(record.inputTokens()).as(record.purpose().name()).isEqualTo(10_000);
            assertThat(record.outputTokens()).isEqualTo(700);
            assertThat(record.cacheReadTokens()).isEqualTo(6_000);
            assertThat(record.cacheWriteTokens()).isEqualTo(3_000);
            assertThat(record.cacheWriteLongTtlTokens()).isEqualTo(1_000);
            assertThat(record.reasoningTokens()).isEqualTo(200);
        });
        // Priced calls: 1,000 plain at $3, 6,000 read at $0.30, 2,000 written at $3.75 and 1,000 at $6, 700 out at $15.
        long pricedMicros = Math.round((1_000 * 3 + 6_000 * 0.3 + 2_000 * 3.75 + 1_000 * 6 + 700 * 15) / 1_000_000.0 * 1_000_000);
        assertThat(records.stream().filter(r -> PRICED.equals(r.model())))
                .isNotEmpty().allSatisfy(r -> assertThat(r.costUsdMicros()).isEqualTo(pricedMicros));
        assertThat(records.stream().filter(r -> r.purpose() == UsagePurpose.EMBEDDING))
                .allSatisfy(r -> assertThat(r.costUsdMicros()).isPositive());

        // Each run's totals are exactly the sum of what it metered.
        for (AgentLoopResult result : results) {
            UsageTotals metered = records.stream().filter(r -> result.execution().id().equals(r.executionId()))
                    .reduce(UsageTotals.ZERO, UsageTotals::plus, UsageTotals::plus);
            assertThat(result.usage()).isEqualTo(metered);
        }
    }

    // ---- delegated runs ----------------------------------------------------------------------------------------

    @Test
    void aDelegatedRunsCallsAreInTheDelegatingRunsTotalsAndMeteredUnderTheirOwnExecution() {
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        AgentLoop specialist = model.loop().usageMeter(records::add).build();
        AgentLoop assistant = model.loop().usageMeter(records::add).tools(new ToolRegistry().register(
                DelegationTools.delegateToAgent(List.of(AgentDelegate.of("finance", "Finance", specialist, RECURSIVE))))).build();
        model.respond(request -> {
            if ("Check the runway".equals(request.getPrompt()) || MockModelGoal.is(request, "Check the runway")) {
                return reported(text("Nine months."));
            }
            return reported(ScriptedModel.lastToolResult(request) == null
                    ? ScriptedModel.toolCall("d1", "delegate_to_agent", Map.of("agent", "finance", "task", "Check the runway"))
                    : text("Your runway is nine months."));
        });

        AgentLoopResult result = assistant.runAndWait(LoopRequest.builder().prompt("How long is our runway?").scope(ALICE)
                .tags(TAGS).routerConfig(priced()).agentProfile(RECURSIVE));

        assertThat(records).hasSize(3);
        Set<UUID> executions = records.stream().map(UsageRecord::executionId).collect(Collectors.toSet());
        assertThat(executions).hasSize(2).contains(result.execution().id());
        UsageTotals all = records.stream().reduce(UsageTotals.ZERO, UsageTotals::plus, UsageTotals::plus);
        assertThat(result.usage()).as("the delegate's call is in the delegating run's totals").isEqualTo(all);
        assertThat(result.usage().calls()).isEqualTo(3);
        assertThat(result.usage().cacheReadTokens()).isEqualTo(18_000);
    }

    // ---- meters --------------------------------------------------------------------------------------------------

    @Test
    void theInMemoryMeterSumsEveryFieldExactlyUnderConcurrentRecording() throws Exception {
        InMemoryUsageMeter meter = new InMemoryUsageMeter();
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        Random random = new Random(7);
        for (int i = 0; i < 2_000; i++) {
            records.add(new UsageRecord(null, Scope.of("acme", "u" + (i % 3), "s"), UsagePurpose.STEP, null, null,
                    random.nextInt(10_000), random.nextInt(1_000), 0, random.nextInt(100_000), Instant.now(), Map.of(),
                    random.nextInt(5_000), random.nextInt(2_000), random.nextInt(500), random.nextInt(300)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (UsageRecord record : records) {
            pool.submit(() -> meter.record(record));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        UsageTotals expected = records.stream().reduce(UsageTotals.ZERO, UsageTotals::plus, UsageTotals::plus);
        assertThat(meter.totals(Scope.of("acme", "u0", "s").atLevel(ScopeLevel.TENANT))).isEqualTo(expected);
        UsageTotals users = UsageTotals.ZERO;
        for (int u = 0; u < 3; u++) {
            users = users.plus(meter.totals(Scope.of("acme", "u" + u, "s").atLevel(ScopeLevel.USER)));
        }
        assertThat(users).isEqualTo(expected);
    }

    /** Matches a delegated run's first call by its goal, wherever the loop puts it. */
    private static final class MockModelGoal {
        static boolean is(Request request, String goal) {
            return goal.equals(io.github.manishpateluk.llmagentloop.testing.MockModel.goal(request));
        }
    }
}
