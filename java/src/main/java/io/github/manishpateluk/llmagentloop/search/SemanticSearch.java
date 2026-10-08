package io.github.manishpateluk.llmagentloop.search;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.tool.office.DocumentText;
import io.github.manishpateluk.llmagentloop.usage.UsageMeter;
import io.github.manishpateluk.llmagentloop.usage.UsagePurpose;
import io.github.manishpateluk.llmagentloop.usage.UsageRecord;
import io.github.manishpateluk.llmagentloop.workspace.Workspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFileInfo;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceListener;
import io.github.manishpateluk.llmrouter.LlmRouter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Semantic (meaning-based) search over workspace files and memory: text is split into chunks,
 * embedded with an {@link Embedder} and kept in a {@link VectorIndex}. Switch it on with
 * {@code AgentLoop.builder().semanticSearch(SemanticSearch.builder().build())}, which then
 * <ul>
 *   <li>indexes workspace files as agents write them ({@link WorkspaceIndexing#ON_WRITE} by default),
 *       including the text of PDFs, Word and PowerPoint files;</li>
 *   <li>gives tools {@code ToolContext.knowledge()}, behind the {@code knowledge_search} tool;</li>
 *   <li>makes memory search hybrid — keyword and semantic, fused — unless {@link Builder#memory} is off
 *       (see {@link SemanticMemoryStore}).</li>
 * </ul>
 *
 * <p>Defaults: embeddings through the loop's own {@code llm-router} ({@link Embedder#router}) and an
 * {@link InMemoryVectorIndex}. Every embedding call is metered as {@link UsagePurpose#EMBEDDING}.
 * Indexing never fails an agent's work: a file that can't be indexed is reported as a warning and
 * stays readable, just not semantically searchable.
 *
 * <p>For your own code: {@link #indexFile}, {@link #removeFile} and {@link #reindexWorkspace} keep the
 * index in step with changes made outside agents (e.g. files your app writes, or back-filling an
 * existing workspace), and {@link #searchWorkspace} searches it directly. Scopes passed here are
 * partition keys — reduce them with {@code scope.atLevel(...)} to the workspace's level first.
 */
public final class SemanticSearch {

    /** {@link VectorIndex} collection holding workspace file chunks. */
    public static final String WORKSPACE = "workspace";
    /** {@link VectorIndex} collection holding memory entries. */
    public static final String MEMORY = "memory";

    /** Texts per embedding call. */
    static final int EMBED_BATCH = 64;

    private static final System.Logger LOG = System.getLogger(SemanticSearch.class.getName());

    /** The run the current thread is executing, so embedding calls made through any path are billed to it. */
    private static final ScopedValue<RunContext> CURRENT_RUN = ScopedValue.newInstance();

    private final Embedder embedder;
    private final VectorIndex index;
    private final WorkspaceIndexing workspaceIndexing;
    private final boolean memory;
    private final UsageMeter meter;
    private final ExecutorService background;
    private final Object backgroundLock = new Object();
    private Future<?> lastBackgroundTask;

    private SemanticSearch(Embedder embedder, VectorIndex index, WorkspaceIndexing workspaceIndexing, boolean memory,
                           UsageMeter meter) {
        this.embedder = embedder;
        this.index = Objects.requireNonNull(index, "index");
        this.workspaceIndexing = Objects.requireNonNull(workspaceIndexing, "workspaceIndexing");
        this.memory = memory;
        this.meter = meter == null ? UsageMeter.NONE : meter;
        this.background = embedder != null && workspaceIndexing == WorkspaceIndexing.ON_WRITE_BACKGROUND
                ? Executors.newSingleThreadExecutor(Thread.ofVirtual().name("semantic-indexer").factory())
                : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * This configuration completed for a loop: the loop's router as the embedder if none was set,
     * and the loop's usage meter. {@code AgentLoop.builder().build()} calls this; you only need it
     * to use a {@code SemanticSearch} on its own.
     */
    public SemanticSearch bind(LlmRouter router, UsageMeter usageMeter) {
        return new SemanticSearch(embedder != null ? embedder : Embedder.router(router), index, workspaceIndexing,
                memory, usageMeter);
    }

    public VectorIndex index() {
        return index;
    }

    public WorkspaceIndexing workspaceIndexing() {
        return workspaceIndexing;
    }

    /** Whether memory search is made hybrid (keyword + semantic). */
    public boolean indexesMemory() {
        return memory;
    }

    // ---- workspace -------------------------------------------------------------------------

    /** Indexes (or re-indexes) one file, replacing whatever was indexed for its path. */
    public void indexFile(Scope scope, WorkspaceFile file) {
        indexFile(scope, file, attribution(scope));
    }

    /** Forgets the file at {@code path}. */
    public void removeFile(Scope scope, String path) {
        index.deleteSource(WORKSPACE, scope, path);
    }

    /**
     * Brings the index in step with {@code workspace} under {@code scope}: indexes files that are new
     * or changed since last indexed (by modification time) and forgets deleted ones. Returns how many
     * files were (re-)indexed. Use it to back-fill an existing workspace, or after writing files
     * directly to your {@code Workspace}; {@link WorkspaceIndexing#ON_SEARCH} calls it before every search.
     */
    public int reindexWorkspace(Scope scope, Workspace workspace) {
        return refresh(scope, workspace, attribution(scope));
    }

    /** Up to {@code limit} workspace passages under {@code scope} most relevant to {@code query}, best first. */
    public List<KnowledgeSearch.Hit> searchWorkspace(Scope scope, String query, int limit) {
        return search(WORKSPACE, scope, query, limit, attribution(scope));
    }

    /** Waits until background indexing ({@link WorkspaceIndexing#ON_WRITE_BACKGROUND}) has caught up; true if it did in time. */
    public boolean awaitIdle(Duration timeout) {
        Future<?> last;
        synchronized (backgroundLock) {
            last = lastBackgroundTask;
        }
        if (last == null) {
            return true;
        }
        try {
            last.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return true; // failures are logged by the task itself
        }
    }

    // ---- wiring for runs -------------------------------------------------------------------

    /**
     * Who a run's embedding calls are billed to, and where its warnings go.
     *
     * @param executionId the run, or {@code null}
     * @param scope       the run's full scope, for usage records
     * @param runUsage    also told about usage that happens on the run's own thread (for its totals)
     * @param warnings    told when indexing fails during the run
     */
    public record RunContext(UUID executionId, Scope scope, Consumer<UsageRecord> runUsage, Consumer<String> warnings) {
        public RunContext {
            Objects.requireNonNull(scope, "scope");
            runUsage = runUsage == null ? record -> { } : runUsage;
            warnings = warnings == null ? warning -> { } : warnings;
        }
    }

    /**
     * Runs {@code body} as part of {@code run}: embedding calls it makes on this thread — memory
     * saves and searches, {@link #indexFile}, {@link #searchWorkspace} and so on — are billed to the
     * run and counted in its totals, rather than to their scope alone. {@code AgentLoop} wraps every
     * run in this; a {@code null} run just runs {@code body}.
     */
    public static void withinRun(RunContext run, Runnable body) {
        if (run == null) {
            body.run();
        } else {
            ScopedValue.where(CURRENT_RUN, run).run(body);
        }
    }

    /**
     * The listener a run's {@code ScopedWorkspace} reports changes to, indexing per
     * {@link #workspaceIndexing()}. {@code partition} is the workspace's scope at its level.
     */
    public WorkspaceListener workspaceListener(Scope partition, RunContext run) {
        if (workspaceIndexing == WorkspaceIndexing.NONE || workspaceIndexing == WorkspaceIndexing.ON_SEARCH) {
            return WorkspaceListener.NONE;
        }
        boolean inBackground = workspaceIndexing == WorkspaceIndexing.ON_WRITE_BACKGROUND;
        Attribution attribution = new Attribution(run.executionId(), run.scope(), inBackground ? null : run.runUsage());
        return new WorkspaceListener() {
            @Override
            public void written(WorkspaceFile file) {
                apply(() -> indexFile(partition, file, attribution), "index " + file.path());
            }

            @Override
            public void deleted(String path) {
                apply(() -> removeFile(partition, path), "remove " + path + " from the search index");
            }

            private void apply(Runnable change, String what) {
                if (inBackground) {
                    submit(change, what);
                    return;
                }
                try {
                    change.run();
                } catch (RuntimeException e) {
                    run.warnings().accept("Couldn't " + what + " for semantic search (" + e.getMessage()
                            + "); the file is saved but knowledge_search won't find it until it's re-indexed.");
                }
            }
        };
    }

    /** What {@code ToolContext.knowledge()} returns for a run: search, refreshing first under {@link WorkspaceIndexing#ON_SEARCH}. */
    public KnowledgeSearch knowledge(Scope partition, Workspace workspace, RunContext run) {
        Attribution attribution = new Attribution(run.executionId(), run.scope(), run.runUsage());
        return (query, limit) -> {
            if (workspaceIndexing == WorkspaceIndexing.ON_SEARCH) {
                refresh(partition, workspace, attribution);
            }
            return search(WORKSPACE, partition, query, limit, attribution);
        };
    }

    // ---- memory (used by SemanticMemoryStore) ----------------------------------------------

    void indexMemory(Scope scope, String id, String content, Map<String, String> metadata) {
        Embedder.Embeddings embeddings = embed(List.of(content), attribution(scope));
        index.upsert(MEMORY, scope, List.of(new VectorIndex.Entry(id, id, "1", content, embeddings.vectors().getFirst(),
                embeddings.model(), metadata)));
    }

    void removeMemory(Scope scope, String id) {
        index.deleteSource(MEMORY, scope, id);
    }

    List<VectorIndex.Match> searchMemory(Scope scope, String query, int limit) {
        Embedder.Embeddings embeddings = embed(List.of(query), attribution(scope));
        return index.search(MEMORY, scope, embeddings.vectors().getFirst(), embeddings.model(), limit);
    }

    // ---- internals -------------------------------------------------------------------------

    /** Billing for embedding calls: {@code runUsage} is null when the call isn't on the run's thread. */
    private record Attribution(UUID executionId, Scope scope, Consumer<UsageRecord> runUsage) {
    }

    /** The current run if there is one (see {@link #withinRun}), else {@code scope} with no execution id. */
    private Attribution attribution(Scope scope) {
        if (CURRENT_RUN.isBound()) {
            RunContext run = CURRENT_RUN.get();
            return new Attribution(run.executionId(), run.scope(), run.runUsage());
        }
        return new Attribution(null, scope, null);
    }

    private void indexFile(Scope scope, WorkspaceFile file, Attribution attribution) {
        Optional<String> text = DocumentText.extract(file);
        if (text.isEmpty()) {
            index.deleteSource(WORKSPACE, scope, file.path()); // e.g. replaced by something unreadable
            return;
        }
        List<TextChunker.Chunk> chunks = TextChunker.chunk(text.get());
        List<VectorIndex.Entry> entries = new ArrayList<>();
        String version = file.modifiedAt().toString();
        for (int from = 0; from < chunks.size(); from += EMBED_BATCH) {
            List<TextChunker.Chunk> batch = chunks.subList(from, Math.min(chunks.size(), from + EMBED_BATCH));
            Embedder.Embeddings embeddings = embed(batch.stream().map(TextChunker.Chunk::text).toList(), attribution);
            for (int i = 0; i < batch.size(); i++) {
                TextChunker.Chunk chunk = batch.get(i);
                entries.add(new VectorIndex.Entry(file.path() + "#" + (from + i), file.path(), version, chunk.text(),
                        embeddings.vectors().get(i), embeddings.model(), Map.of("start", String.valueOf(chunk.start()))));
            }
        }
        // Embed everything first, so a failure part-way leaves the previous version searchable.
        index.deleteSource(WORKSPACE, scope, file.path());
        index.upsert(WORKSPACE, scope, entries);
    }

    private int refresh(Scope scope, Workspace workspace, Attribution attribution) {
        Map<String, String> indexed = index.sourceVersions(WORKSPACE, scope);
        Set<String> present = new HashSet<>();
        int reindexed = 0;
        for (WorkspaceFileInfo info : workspace.list(scope)) {
            present.add(info.path());
            if (!info.modifiedAt().toString().equals(indexed.get(info.path()))) {
                Optional<WorkspaceFile> file = workspace.read(scope, info.path());
                if (file.isPresent()) {
                    indexFile(scope, file.get(), attribution);
                    reindexed++;
                }
            }
        }
        for (String source : indexed.keySet()) {
            if (!present.contains(source)) {
                index.deleteSource(WORKSPACE, scope, source);
            }
        }
        return reindexed;
    }

    private List<KnowledgeSearch.Hit> search(String collection, Scope scope, String query, int limit, Attribution attribution) {
        Embedder.Embeddings embeddings = embed(List.of(query), attribution);
        return index.search(collection, scope, embeddings.vectors().getFirst(), embeddings.model(), limit).stream()
                .map(match -> new KnowledgeSearch.Hit(match.entry().source(), match.entry().text(), match.score(),
                        match.entry().metadata()))
                .toList();
    }

    private Embedder.Embeddings embed(List<String> texts, Attribution attribution) {
        if (embedder == null) {
            throw new IllegalStateException("SemanticSearch isn't bound to a router: use it through AgentLoop.builder(), "
                    + "call bind(router, meter), or set an embedder");
        }
        Embedder.Embeddings embeddings = embedder.embed(texts);
        if (embeddings.vectors().size() != texts.size()) {
            throw new IllegalStateException("Embedder returned " + embeddings.vectors().size() + " vectors for "
                    + texts.size() + " texts");
        }
        UsageRecord record = new UsageRecord(attribution.executionId(), attribution.scope(), UsagePurpose.EMBEDDING,
                embeddings.provider(), embeddings.model(), embeddings.inputTokens(), 0,
                Math.round(embeddings.costUsdMicros() / 10_000.0), embeddings.costUsdMicros(), Instant.now());
        meter.record(record);
        if (attribution.runUsage() != null) {
            attribution.runUsage().accept(record);
        }
        return embeddings;
    }

    private void submit(Runnable change, String what) {
        synchronized (backgroundLock) {
            lastBackgroundTask = background.submit(() -> {
                try {
                    change.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Couldn't " + what + " for semantic search", e);
                }
            });
        }
    }

    /** See {@link SemanticSearch}. */
    public static final class Builder {

        private Embedder embedder;
        private VectorIndex index = new InMemoryVectorIndex();
        private WorkspaceIndexing workspaceIndexing = WorkspaceIndexing.ON_WRITE;
        private boolean memory = true;

        private Builder() {
        }

        /** How text becomes vectors; defaults to the loop's router ({@link Embedder#router}). */
        public Builder embedder(Embedder embedder) {
            this.embedder = Objects.requireNonNull(embedder, "embedder");
            return this;
        }

        /** Where vectors are kept; defaults to a new {@link InMemoryVectorIndex}. */
        public Builder index(VectorIndex index) {
            this.index = Objects.requireNonNull(index, "index");
            return this;
        }

        /** When workspace files are indexed; defaults to {@link WorkspaceIndexing#ON_WRITE}. */
        public Builder workspaceIndexing(WorkspaceIndexing indexing) {
            this.workspaceIndexing = Objects.requireNonNull(indexing, "indexing");
            return this;
        }

        /** Whether memory search becomes hybrid (keyword + semantic); on by default. */
        public Builder memory(boolean memory) {
            this.memory = memory;
            return this;
        }

        public SemanticSearch build() {
            return new SemanticSearch(embedder, index, workspaceIndexing, memory, null);
        }
    }
}
