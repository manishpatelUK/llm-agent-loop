package io.github.manishpateluk.llmagentloop.search;

import io.github.manishpateluk.llmagentloop.AgentLoop;
import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.MessageType;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.ScopeLevel;
import io.github.manishpateluk.llmagentloop.memory.InMemoryMemoryStore;
import io.github.manishpateluk.llmagentloop.memory.MemoryEntry;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.testing.TestRuns;
import io.github.manishpateluk.llmagentloop.testing.TestRuns.TestRun;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.KnowledgeTools;
import io.github.manishpateluk.llmagentloop.tool.builtin.MemoryTools;
import io.github.manishpateluk.llmagentloop.tool.builtin.WorkspaceTools;
import io.github.manishpateluk.llmagentloop.usage.UsagePurpose;
import io.github.manishpateluk.llmagentloop.usage.UsageRecord;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.MediaTypes;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.model.EmbeddingResponse;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticSearchTest {

    private static final Scope SCOPE = Scope.of("acme", "alice", "s1");
    private static final Scope PARTITION = SCOPE.atLevel(ScopeLevel.USER);
    private static final String CONTRACT = "The termination clause requires thirty days written warning from either party.";
    private static final Map<String, Object> WRITE_CONTRACT = Map.of("path", "contracts/acme.md", "content", CONTRACT);
    private static final Map<String, Object> SEARCH_CANCEL = Map.of("query", "how much notice to cancel");

    private final WordEmbedder embedder = new WordEmbedder();
    private final InMemoryWorkspace workspace = new InMemoryWorkspace();
    private final List<UsageRecord> usage = new CopyOnWriteArrayList<>();

    private AgentLoop loop(MockModel model, WorkspaceIndexing indexing) {
        ToolRegistry tools = new ToolRegistry().registerAll(WorkspaceTools.all()).registerAll(KnowledgeTools.all())
                .registerAll(MemoryTools.all());
        return model.loopBuilder().tools(tools).workspace(workspace).usageMeter(usage::add)
                .semanticSearch(SemanticSearch.builder().embedder(embedder).workspaceIndexing(indexing).build())
                .build();
    }

    private static LoopRequest.LoopRequestBuilder request(String prompt) {
        return LoopRequest.builder().prompt(prompt).agentProfile(MockModel.STEP_BY_STEP).scope(SCOPE);
    }

    private static String toolResult(MockModel model, int call) {
        return MockModel.lastToolResult(model.requests().get(call));
    }

    @Test
    void onWriteIsTheDefaultAndIndexesBeforeTheWriteReturns() {
        assertThat(SemanticSearch.builder().build().workspaceIndexing()).isEqualTo(WorkspaceIndexing.ON_WRITE);

        MockModel model = new MockModel()
                .callTool(WorkspaceTools.WRITE, WRITE_CONTRACT)
                .callTool(KnowledgeTools.SEARCH, SEARCH_CANCEL)
                .reply("Thirty days.");
        TestRun run = TestRuns.run(loop(model, WorkspaceIndexing.ON_WRITE), request("What's the notice period?"));

        assertThat(run.answer()).isEqualTo("Thirty days.");
        assertThat(toolResult(model, 2)).contains("1. contracts/acme.md (relevance", CONTRACT);
        // Every embedding call is metered against the run, and counted in its totals.
        List<UsageRecord> embeddings = usage.stream().filter(r -> r.purpose() == UsagePurpose.EMBEDDING).toList();
        assertThat(embeddings).hasSize(2).allSatisfy(record -> {
            assertThat(record.executionId()).isEqualTo(run.result().execution().id());
            assertThat(record.scope()).isEqualTo(SCOPE);
            assertThat(record.model()).isEqualTo("test/words-64");
        });
        assertThat(run.result().usage().calls()).isEqualTo(usage.size());
    }

    @Test
    void searchResultsAreLabelledAsUntrustedContent() {
        assertThat(KnowledgeTools.search().untrustedOutput()).isTrue();
    }

    @Test
    void deletingAFileThroughAnAgentRemovesItFromTheIndex() {
        MockModel model = new MockModel()
                .callTool(WorkspaceTools.WRITE, WRITE_CONTRACT)
                .callTool(WorkspaceTools.DELETE, Map.of("path", "contracts/acme.md"))
                .callTool(KnowledgeTools.SEARCH, SEARCH_CANCEL)
                .reply("Nothing found.");
        TestRuns.run(loop(model, WorkspaceIndexing.ON_WRITE), request("Tidy up"));

        assertThat(toolResult(model, 3)).contains("No matching passages");
    }

    @Test
    void noneIndexesNothingUntilAskedToReindex() {
        MockModel model = new MockModel()
                .callTool(WorkspaceTools.WRITE, WRITE_CONTRACT)
                .callTool(KnowledgeTools.SEARCH, SEARCH_CANCEL)
                .reply("Not found.");
        AgentLoop loop = loop(model, WorkspaceIndexing.NONE);
        TestRuns.run(loop, request("What's the notice period?"));

        assertThat(toolResult(model, 2)).contains("No matching passages");

        assertThat(loop.semanticSearch().reindexWorkspace(PARTITION, workspace)).isEqualTo(1);
        assertThat(loop.semanticSearch().searchWorkspace(PARTITION, "cancel notice", 3))
                .extracting(KnowledgeSearch.Hit::source).containsExactly("contracts/acme.md");
        // Nothing changed since, so a second reindex does no work.
        assertThat(loop.semanticSearch().reindexWorkspace(PARTITION, workspace)).isZero();
    }

    @Test
    void onWriteBackgroundIndexesWithoutHoldingUpTheWrite() {
        embedder.gate = new CountDownLatch(1);
        MockModel model = new MockModel()
                .callTool(WorkspaceTools.WRITE, WRITE_CONTRACT)
                .reply("Saved.");
        AgentLoop loop = loop(model, WorkspaceIndexing.ON_WRITE_BACKGROUND);

        TestRun run = TestRuns.run(loop, request("Save the contract"));

        // The run finished while the embedder was still blocked: indexing hadn't happened yet.
        assertThat(run.answer()).isEqualTo("Saved.");
        assertThat(loop.semanticSearch().index().sourceVersions(SemanticSearch.WORKSPACE, PARTITION)).isEmpty();

        embedder.gate.countDown();
        assertThat(loop.semanticSearch().awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(loop.semanticSearch().index().sourceVersions(SemanticSearch.WORKSPACE, PARTITION))
                .containsOnlyKeys("contracts/acme.md");
        // Background usage is still metered, but outside the run's own totals.
        assertThat(usage).anySatisfy(record -> assertThat(record.purpose()).isEqualTo(UsagePurpose.EMBEDDING));
    }

    @Test
    void onSearchCatchesUpWithFilesWrittenOutsideTheAgentAndForgetsDeletedOnes() {
        workspace.write(PARTITION, new WorkspaceFile("contracts/acme.md", "text/markdown",
                CONTRACT.getBytes(StandardCharsets.UTF_8), Instant.now()));
        MockModel model = new MockModel()
                .callTool(KnowledgeTools.SEARCH, SEARCH_CANCEL).reply("Thirty days.")
                .callTool(KnowledgeTools.SEARCH, SEARCH_CANCEL).reply("Gone.");
        AgentLoop loop = loop(model, WorkspaceIndexing.ON_SEARCH);

        TestRuns.run(loop, request("What's the notice period?"));
        assertThat(toolResult(model, 1)).contains("contracts/acme.md");

        workspace.delete(PARTITION, "contracts/acme.md");
        TestRuns.run(loop, request("And now?"));
        assertThat(toolResult(model, 3)).contains("No matching passages");
    }

    @Test
    void onSearchDoesNotIndexAtWriteTime() {
        MockModel model = new MockModel().callTool(WorkspaceTools.WRITE, WRITE_CONTRACT).reply("Saved.");
        AgentLoop loop = loop(model, WorkspaceIndexing.ON_SEARCH);
        TestRuns.run(loop, request("Save it"));

        assertThat(embedder.calls.get()).isZero();
    }

    @Test
    void anIndexingFailureIsAWarningAndTheFileIsStillSaved() {
        embedder.failWith = new IllegalStateException("embeddings unavailable");
        MockModel model = new MockModel().callTool(WorkspaceTools.WRITE, WRITE_CONTRACT).reply("Saved.");

        TestRun run = TestRuns.run(loop(model, WorkspaceIndexing.ON_WRITE), request("Save it"));

        assertThat(run.answer()).isEqualTo("Saved.");
        assertThat(toolResult(model, 1)).startsWith("Wrote ");
        assertThat(workspace.read(PARTITION, "contracts/acme.md")).isPresent();
        assertThat(run.messages(MessageType.WARNING))
                .anySatisfy(warning -> assertThat(warning).contains("Couldn't index contracts/acme.md", "embeddings unavailable"));
    }

    @Test
    void knowledgeSearchWithoutSemanticSearchConfiguredFailsClearly() {
        MockModel model = new MockModel().callTool(KnowledgeTools.SEARCH, SEARCH_CANCEL).reply("Can't search.");
        AgentLoop loop = model.loopBuilder().tools(new ToolRegistry().registerAll(KnowledgeTools.all())).build();

        TestRun run = TestRuns.run(loop, request("Search"));

        // Like memory and workspace tools without a store, it's a configuration error that ends the run.
        assertThat(run.error()).hasMessageContaining("Semantic search isn't configured");
        assertThat(loop.semanticSearch()).isNull();
    }

    @Test
    void indexesTheTextOfWordDocumentsAndSplitsLongFilesIntoPassages() throws Exception {
        SemanticSearch search = SemanticSearch.builder().embedder(embedder).build().bind(null, usage::add);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            for (int i = 0; i < 60; i++) {
                document.createParagraph().createRun().setText("Section " + i + " covers payment schedules and invoices in detail.");
            }
            document.createParagraph().createRun().setText(CONTRACT);
            document.write(bytes);
        }
        search.indexFile(PARTITION, new WorkspaceFile("contracts/msa.docx", MediaTypes.DOCX, bytes.toByteArray(), Instant.now()));

        List<KnowledgeSearch.Hit> hits = search.searchWorkspace(PARTITION, "terminate with notice", 1);
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.source()).isEqualTo("contracts/msa.docx");
            assertThat(hit.text()).contains("termination clause");
            assertThat(hit.metadata()).containsKey("start");
        });
        assertThat(search.index().search(SemanticSearch.WORKSPACE, PARTITION, new float[64], "test/words-64", 100))
                .hasSizeGreaterThan(1);
        // Usage outside a run has no execution id and is billed to the scope it was given.
        assertThat(usage).allSatisfy(record -> {
            assertThat(record.executionId()).isNull();
            assertThat(record.scope()).isEqualTo(PARTITION);
        });
    }

    @Test
    void filesWithNoTextAreNotIndexed() {
        SemanticSearch search = SemanticSearch.builder().embedder(embedder).build().bind(null, usage::add);
        search.indexFile(PARTITION, new WorkspaceFile("logo.png", "image/png", new byte[]{1, 2, 3}, Instant.now()));

        assertThat(embedder.calls.get()).isZero();
        assertThat(search.index().sourceVersions(SemanticSearch.WORKSPACE, PARTITION)).isEmpty();
    }

    @Test
    void hybridMemoryFindsEntriesByMeaningAsWellAsByWords() {
        SemanticSearch search = SemanticSearch.builder().embedder(embedder).build().bind(null, usage::add);
        SemanticMemoryStore memory = new SemanticMemoryStore(new InMemoryMemoryStore(), search);
        memory.save(PARTITION, "Moved to Leeds in May; it has been home since.", List.of("personal"));
        memory.save(PARTITION, "Prefers invoices as PDF.", List.of());

        // No word in common with the first entry, but "residence" means "home".
        assertThat(new InMemoryMemoryStore().search(PARTITION, "residence", 5)).isEmpty();
        List<MemoryEntry> found = memory.search(PARTITION, "residence", 1);
        assertThat(found).singleElement().satisfies(entry -> {
            assertThat(entry.content()).startsWith("Moved to Leeds");
            assertThat(entry.tags()).containsExactly("personal");
        });
        // A keyword match still ranks first.
        assertThat(memory.search(PARTITION, "invoices", 1)).extracting(MemoryEntry::content).containsExactly("Prefers invoices as PDF.");

        String id = memory.search(PARTITION, "residence", 1).getFirst().id();
        assertThat(memory.delete(PARTITION, id)).isTrue();
        assertThat(memory.search(PARTITION, "residence", 5)).isEmpty();
    }

    @Test
    void hybridMemoryFallsBackToKeywordsWhenEmbeddingFails() {
        SemanticSearch search = SemanticSearch.builder().embedder(embedder).build().bind(null, usage::add);
        SemanticMemoryStore memory = new SemanticMemoryStore(new InMemoryMemoryStore(), search);
        embedder.failWith = new IllegalStateException("down");

        MemoryEntry saved = memory.save(PARTITION, "Prefers invoices as PDF.", List.of());

        assertThat(saved.id()).isNotBlank();
        assertThat(memory.search(PARTITION, "invoices", 5)).extracting(MemoryEntry::id).containsExactly(saved.id());
    }

    @Test
    void theLoopMakesConfiguredMemoryHybridUnlessSwitchedOff() {
        MockModel model = new MockModel()
                .callTool(MemoryTools.SAVE, Map.of("content", "Moved to Leeds in May; it has been home since."))
                .callTool(MemoryTools.SEARCH, Map.of("query", "residence"))
                .reply("Leeds.");
        ToolRegistry tools = new ToolRegistry().registerAll(MemoryTools.all());
        AgentLoop loop = model.loopBuilder().tools(tools).memory(new InMemoryMemoryStore()).usageMeter(usage::add)
                .semanticSearch(SemanticSearch.builder().embedder(embedder).build()).build();
        TestRun run = TestRuns.run(loop, request("Where do I live?"));
        assertThat(toolResult(model, 2)).contains("Moved to Leeds");
        // Memory embeddings (the save, the search, and each step's automatic recall) are billed to the run.
        List<UsageRecord> embeddings = usage.stream().filter(r -> r.purpose() == UsagePurpose.EMBEDDING).toList();
        assertThat(embeddings).hasSizeGreaterThanOrEqualTo(2).allSatisfy(record -> {
            assertThat(record.executionId()).isEqualTo(run.result().execution().id());
            assertThat(record.scope()).isEqualTo(SCOPE);
        });
        assertThat(run.result().usage().calls()).isEqualTo(usage.size());

        MockModel keywordOnly = new MockModel()
                .callTool(MemoryTools.SAVE, Map.of("content", "Moved to Leeds in May; it has been home since."))
                .callTool(MemoryTools.SEARCH, Map.of("query", "residence"))
                .reply("Don't know.");
        AgentLoop off = keywordOnly.loopBuilder().tools(tools).memory(new InMemoryMemoryStore())
                .semanticSearch(SemanticSearch.builder().embedder(embedder).memory(false).build()).build();
        TestRuns.run(off, request("Where do I live?"));
        assertThat(toolResult(keywordOnly, 2)).doesNotContain("Moved to Leeds");
    }

    @Test
    void routerEmbedderUsesLlmRoutersEmbeddingsAndReportsModelAndTokens() {
        List<List<String>> received = new ArrayList<>();
        ProviderAdapter adapter = new ProviderAdapter() {
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
                return "emb-1";
            }

            @Override
            public EmbeddingResponse embed(String model, List<String> texts, Integer dimensions) {
                received.add(texts);
                return EmbeddingResponse.builder()
                        .vectors(texts.stream().map(text -> new float[]{text.length(), 1}).toList())
                        .usage(Usage.builder().inputTokens(7).build()).build();
            }
        };

        Embedder.Embeddings embeddings = Embedder.router(new LlmRouter(List.of(adapter))).embed(List.of("a", "bb"));

        assertThat(received).containsExactly(List.of("a", "bb"));
        assertThat(embeddings.vectors()).hasSize(2);
        assertThat(embeddings.vectors().get(1)).containsExactly(2, 1);
        assertThat(embeddings.model()).endsWith("/emb-1");
        assertThat(embeddings.provider()).isEqualTo(Provider.OPENAI);
        assertThat(embeddings.inputTokens()).isEqualTo(7);
    }

    @Test
    void memoryStoreNoneIsNotWrapped() {
        AgentLoop loop = new MockModel().loopBuilder()
                .semanticSearch(SemanticSearch.builder().embedder(embedder).build()).build();
        assertThat(loop.semanticSearch().indexesMemory()).isTrue();
        assertThat(MemoryStore.NONE.search(PARTITION, "x", 1)).isEmpty();
    }
}
