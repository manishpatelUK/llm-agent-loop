package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmagentloop.compression.CompressionMethod;
import io.github.manishpateluk.llmagentloop.compression.HistoryCompressor;
import io.github.manishpateluk.llmagentloop.conversation.ConversationCompaction;
import io.github.manishpateluk.llmagentloop.conversation.InMemoryConversationStore;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.testing.TestRuns;
import io.github.manishpateluk.llmagentloop.testing.TestRuns.TestRun;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.WorkspaceTools;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Less common paths through a run: fallbacks mid-stream, failures that must only warn, bad plans. */
class RunEdgeCasesTest {

    private static final String FIRST = "test-edge-first";
    private static final String SECOND = "test-edge-second";
    private static final String TINY = "test-edge-tiny";
    private static final Scope SCOPE = Scope.of("acme", "alice", "s1");
    private static final AgentProfile ALWAYS_PLAN = AgentProfile.builder().planMode(PlanMode.ALWAYS_PLAN).build();

    @BeforeEach
    void registerModels() {
        for (String model : List.of(FIRST, SECOND)) {
            ModelCapabilityTable.registerModel(ModelEntry.builder().provider(model.equals(FIRST) ? Provider.ANTHROPIC : Provider.OPENAI)
                    .model(model).contextWindowTokens(100_000).maxOutputTokens(4_000).supportsTools(true).build());
        }
        ModelCapabilityTable.registerModel(ModelEntry.builder().provider(Provider.ANTHROPIC).model(TINY)
                .contextWindowTokens(1_000).maxOutputTokens(0).build());
    }

    @AfterEach
    void removeModels() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, FIRST);
        ModelCapabilityTable.removeModel(Provider.OPENAI, SECOND);
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, TINY);
    }

    /** A provider that streams {@code text} word by word — and, if {@code failAfterFirstWord}, then fails. */
    private static ProviderAdapter streaming(Provider provider, String text, boolean failAfterFirstWord) {
        return new ProviderAdapter() {
            @Override
            public Provider id() {
                return provider;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Response send(String model, Request adaptedRequest) {
                return Response.builder().content(text).usage(Usage.builder().inputTokens(1).outputTokens(1).build()).build();
            }

            @Override
            public Response sendStreaming(String model, Request adaptedRequest, Consumer<String> onText) {
                String[] words = text.split("(?<= )");
                for (String word : words) {
                    onText.accept(word);
                    if (failAfterFirstWord) {
                        throw new IllegalStateException("connection reset mid-stream");
                    }
                }
                return send(model, adaptedRequest);
            }
        };
    }

    @Test
    void aFallbackPartWayThroughAStreamedAnswerDiscardsWhatWasShown() {
        LlmRouter router = new LlmRouter(List.of(
                streaming(Provider.ANTHROPIC, "Half an answer from the first model.", true),
                streaming(Provider.OPENAI, "The full answer.", false)));
        AgentLoop loop = AgentLoop.builder().router(router).build();

        TestRun run = TestRuns.run(loop, LoopRequest.builder().prompt("Question?").agentProfile(MockModel.STEP_BY_STEP)
                .routerConfig(RouterConfig.builder().route(List.of(
                        RouteEntry.of(Provider.ANTHROPIC, FIRST), RouteEntry.of(Provider.OPENAI, SECOND))).build()));

        assertThat(run.error()).isNull();
        assertThat(run.streamed()).containsExactly("Half ", "<discard>", "The ", "full ", "answer.");
        assertThat(run.shownAnswer()).isEqualTo(run.answer()).isEqualTo("The full answer.");
    }

    @Test
    void historyThatCannotBeCompressedEnoughIsReportedBeforeTheRouterGivesUp() {
        List<Request> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Pin compression to a 1,000-token model, with only a method that can't shrink a single huge prompt.
        LlmRouter router = new LlmRouter(List.of(new FakeProviderAdapter(request -> {
                    sent.add(request);
                    return Response.builder().content("unreachable").build();
                })),
                (provider, name, request) -> HistoryCompressor.compress(request, Provider.ANTHROPIC, TINY,
                        List.of(CompressionMethod.STRUCTURAL_COMPACTION)).request());
        AgentLoop loop = new AgentLoop(router);

        TestRun run = TestRuns.run(loop, LoopRequest.builder().prompt("x ".repeat(20_000)).agentProfile(MockModel.STEP_BY_STEP));

        assertThat(run.error()).isNotNull();
        assertThat(run.messages(MessageType.WARNING))
                .anySatisfy(warning -> assertThat(warning).startsWith("Could not compress history to fit"));
        assertThat(sent).isEmpty();
    }

    @Test
    void aStructuredAnswerCallThatFailsOnlyWarns() {
        MockModel model = new MockModel()
                .reply("Revenue was 12.")
                .respond(request -> {
                    throw new IllegalStateException("formatter unavailable");
                });

        TestRun run = TestRuns.run(model.loopBuilder().build(), LoopRequest.builder().prompt("Revenue?")
                .agentProfile(MockModel.STEP_BY_STEP)
                .answerSchema(Map.of("type", "object", "properties", Map.of("revenue", Map.of("type", "number")))));

        assertThat(run.error()).isNull();
        assertThat(run.answer()).isEqualTo("Revenue was 12.");
        assertThat(run.result().structuredAnswer()).isNull();
        assertThat(run.messages(MessageType.WARNING))
                .anySatisfy(warning -> assertThat(warning).startsWith("Couldn't turn the answer into the requested structure (")
                        .contains("structuredAnswer is null"));
    }

    @Test
    void aCompactorThatThrowsLeavesTheConversationAsItWasWithAWarning() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        MockModel model = new MockModel().reply("one").reply("two");
        AgentLoop loop = model.loopBuilder().conversations(store)
                .conversationCompaction(new ConversationCompaction(2, 1, (older, summarizer, session) -> {
                    throw new IllegalStateException("archive offline");
                }))
                .build();

        TestRuns.run(loop, LoopRequest.builder().prompt("first").agentProfile(MockModel.STEP_BY_STEP).scope(SCOPE));
        TestRun second = TestRuns.run(loop, LoopRequest.builder().prompt("second").agentProfile(MockModel.STEP_BY_STEP).scope(SCOPE));

        assertThat(second.error()).isNull();
        assertThat(second.answer()).isEqualTo("two");
        assertThat(second.messages(MessageType.WARNING))
                .anySatisfy(warning -> assertThat(warning).contains("Couldn't compact the conversation (archive offline)"));
        assertThat(store.load(SCOPE)).hasSize(4);
    }

    @Test
    void anAttachmentThatCannotBeSavedIsStillDescribedToTheModel() {
        MockModel model = new MockModel().reply("Got it.");
        AgentLoop loop = model.loopBuilder().workspace(new InMemoryWorkspace())
                .workspaceLimits(new WorkspaceLimits(10, 4, 1_000)).build();

        TestRun run = TestRuns.run(loop, LoopRequest.builder().prompt("Read this").agentProfile(MockModel.STEP_BY_STEP).scope(SCOPE)
                .attachments(List.of(InputFile.of("notes.txt", "far more than four bytes".getBytes()))));

        assertThat(run.error()).isNull();
        assertThat(run.result().changedFiles()).isEmpty();
        String seen = model.requests().getFirst().getPrompt() + model.requests().getFirst().getHistory();
        assertThat(seen).contains("notes.txt", "couldn't be saved to the workspace", "far more than four bytes");
    }

    @Test
    void aPlanWithNoStepsFailsTheRun() {
        MockModel model = new MockModel().plan();

        TestRun run = TestRuns.run(model.loopBuilder().build(),
                LoopRequest.builder().prompt("Do a thing").agentProfile(ALWAYS_PLAN));

        assertThat(run.error()).hasMessageContaining("Plan had no steps");
    }

    @Test
    void aPlanCallWithNoStructuredOutputFailsTheRun() {
        MockModel model = new MockModel().respond(request -> Response.builder().content("").build());

        TestRun run = TestRuns.run(model.loopBuilder().build(),
                LoopRequest.builder().prompt("Do a thing").agentProfile(ALWAYS_PLAN));

        assertThat(run.error()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plan generation returned no structured output");
        assertThat(run.result()).isNull();
    }

    @Test
    void atMostTenViewedFilesAreShownToTheModelAndTheOldestDropsOff() {
        InMemoryWorkspace workspace = new InMemoryWorkspace();
        Scope partition = SCOPE.atLevel(ScopeLevel.USER);
        MockModel model = new MockModel();
        for (int i = 1; i <= 11; i++) {
            workspace.write(partition, new io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile(
                    "images/" + i + ".png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'}, Instant.now()));
            model.callTool(WorkspaceTools.VIEW, new HashMap<>(Map.of("path", "images/" + i + ".png")));
        }
        model.reply("Seen them.");
        AgentLoop loop = model.loopBuilder().workspace(workspace)
                .tools(new ToolRegistry().registerAll(WorkspaceTools.all())).build();

        TestRun run = TestRuns.run(loop, LoopRequest.builder().prompt("Look at the images").agentProfile(MockModel.STEP_BY_STEP).scope(SCOPE));

        assertThat(run.error()).isNull();
        Map<String, Object> shown = new LinkedHashMap<>();
        model.requests().getLast().getAttachments().forEach(a -> shown.put(a.getFilename(), a));
        assertThat(shown.keySet()).hasSize(10).doesNotContain("images/1.png").contains("images/2.png", "images/11.png");
    }
}
