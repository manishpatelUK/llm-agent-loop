package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.memory.MemoryEntry;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.memory.RecallPolicy;
import io.github.manishpateluk.llmagentloop.memory.RecallRequest;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.manishpateluk.llmagentloop.AgentLoopRunSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/** Which memories each working step is reminded of: the {@link RecallPolicy}, and how recalled entries read. */
class RecallPolicyTest {

    private static final String MODEL = "test-recall-policy-model";
    private static final Scope SCOPE = Scope.of("acme", "alice", "s1");
    private static final AgentProfile RECURSIVE =
            AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build();

    private final Deque<Response> script = new ArrayDeque<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final CountingStore memory = new CountingStore();
    private LlmRouter router;

    @BeforeEach
    void setUp() {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.ANTHROPIC).model(MODEL)
                .contextWindowTokens(100_000).maxOutputTokens(4_000)
                .supportsTools(true).supportsStructuredOutput(true)
                .build());
        router = new LlmRouter(List.of(new FakeProviderAdapter(request -> {
            requests.add(request);
            return script.removeFirst();
        })));
    }

    @AfterEach
    void tearDown() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, MODEL);
    }

    @Test
    void byDefaultMemoryIsSearchedBeforeEveryStep() throws InterruptedException {
        AgentLoop loop = loop(null);
        threeSteps(loop);

        assertThat(memory.searches.get()).isEqualTo(3);
        assertThat(requests).allSatisfy(r -> assertThat(r.getPrompt()).contains("Relevant memory", "Prefers short answers"));
    }

    @Test
    void oncePerRunSearchesOnTheFirstStepAndShowsTheSameMemoriesAfter() throws InterruptedException {
        AgentLoop loop = loop(RecallPolicy.oncePerRun(5));
        threeSteps(loop);

        assertThat(memory.searches.get()).isEqualTo(1);
        assertThat(requests).hasSize(3)
                .allSatisfy(r -> assertThat(r.getPrompt()).contains("Relevant memory", "Prefers short answers"));
    }

    @Test
    void noneRecallsNothing() throws InterruptedException {
        AgentLoop loop = loop(RecallPolicy.NONE);
        threeSteps(loop);

        assertThat(memory.searches.get()).isZero();
        assertThat(requests).noneSatisfy(r -> assertThat(r.getPrompt()).contains("Relevant memory"));
    }

    @Test
    void aPolicyIsToldTheStepWhatItGaveBeforeAndTheRunsTags() throws InterruptedException {
        List<RecallRequest> seen = new CopyOnWriteArrayList<>();
        AgentLoop loop = loop(request -> {
            seen.add(request);
            return List.of();
        });
        script.add(toolCall("t1", "ping"));
        script.add(complete("done"));
        run(loop, LoopRequest.builder().prompt("do it").agentProfile(RECURSIVE).scope(SCOPE).tags(Map.of("team", "blue")));

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).step()).isZero();
        assertThat(seen.get(0).previous()).isNull();
        assertThat(seen.get(1).step()).isEqualTo(1);
        assertThat(seen.get(1).previous()).isEmpty();
        assertThat(seen).allSatisfy(r -> {
            assertThat(r.goal()).contains("do it");
            assertThat(r.tags()).containsEntry("team", "blue");
            assertThat(r.memory().scope().userId()).isEqualTo("alice");
        });
    }

    @Test
    void aFailingPolicyRecallsNothingAndTheRunCarriesOn() throws InterruptedException {
        AgentLoop loop = loop(request -> {
            throw new IllegalStateException("store down");
        });
        script.add(complete("done"));
        AgentLoopRunSupport.Capture capture = run(loop, LoopRequest.builder().prompt("do it").agentProfile(RECURSIVE).scope(SCOPE));

        assertThat(capture.error()).isNull();
        assertThat(requests.getFirst().getPrompt()).doesNotContain("Relevant memory");
        assertThat(capture.messages()).anyMatch(m -> m.type() == MessageType.WARNING && m.message().contains("store down"));
    }

    @Test
    void recalledEntriesWithASourceOrAgeAreLabelled() throws InterruptedException {
        AgentLoop loop = loop(RecallPolicy.everyStep(5));
        script.add(complete("done"));
        run(loop, LoopRequest.builder().prompt("do it").agentProfile(RECURSIVE).scope(SCOPE));

        assertThat(requests.getFirst().getPrompt())
                .contains("- [user, 3 days ago] Prefers short answers")
                .contains("- Works in Leeds");
    }

    @Test
    void anEntryReadsWithWhicheverLabelsItHas() {
        Instant now = Instant.now();
        assertThat(new MemoryEntry("1", "fact", List.of(), now).recalledText()).isEqualTo("fact");
        assertThat(new MemoryEntry("1", "fact", List.of(), now, Map.of(MemoryEntry.SOURCE, "user")).recalledText())
                .isEqualTo("[user] fact");
        assertThat(new MemoryEntry("1", "fact", List.of(), now, Map.of(MemoryEntry.AGE, "today")).recalledText())
                .isEqualTo("[today] fact");
        assertThat(new MemoryEntry("1", "fact", List.of(), now, null).metadata()).isEmpty();
    }

    private AgentLoop loop(RecallPolicy recall) {
        AgentLoop.Builder builder = AgentLoop.builder()
                .router(router)
                .tools(new ToolRegistry().register(ToolDefinition.builder().name("ping").description("Answers pong")
                        .parameters(Map.of("type", "object")).build(), args -> "pong"))
                .memory(memory);
        return (recall == null ? builder : builder.recall(recall)).build();
    }

    /** A run of three model calls: two tool calls, then the answer. */
    private void threeSteps(AgentLoop loop) throws InterruptedException {
        script.add(toolCall("t1", "ping"));
        script.add(toolCall("t2", "ping"));
        script.add(complete("done"));
        assertThat(run(loop, LoopRequest.builder().prompt("do it").agentProfile(RECURSIVE).scope(SCOPE)).error()).isNull();
    }

    private static Response toolCall(String id, String name) {
        return Response.builder().content("")
                .toolCalls(List.of(ToolCall.builder().id(id).name(name).arguments(Map.of()).build())).build();
    }

    private static Response complete(String answer) {
        return Response.builder().content(answer).build();
    }

    /** Two memories, one with metadata; counts searches. */
    private static final class CountingStore implements MemoryStore {
        final AtomicInteger searches = new AtomicInteger();

        @Override
        public MemoryEntry save(Scope scope, String content, List<String> tags) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<MemoryEntry> search(Scope scope, String query, int limit) {
            searches.incrementAndGet();
            Instant now = Instant.now();
            return List.of(
                    new MemoryEntry("m1", "Prefers short answers", List.of(), now,
                            Map.of(MemoryEntry.SOURCE, "user", MemoryEntry.AGE, "3 days ago")),
                    new MemoryEntry("m2", "Works in Leeds", List.of(), now));
        }

        @Override
        public boolean delete(Scope scope, String id) {
            return false;
        }
    }
}
