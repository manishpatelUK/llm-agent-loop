package io.github.manishpateluk.llmagentloop.search;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.MemoryEntry;
import io.github.manishpateluk.llmagentloop.memory.MemoryStore;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Makes any {@link MemoryStore}'s search hybrid: its own (usually keyword) results and semantic
 * matches from a {@link SemanticSearch} index are merged with reciprocal-rank fusion, so a memory
 * is found by meaning ("where does the user live?" finds "Moved to Leeds in May") as well as by
 * words. {@code AgentLoop} wraps the configured memory in one automatically when semantic search is
 * on; storage stays in the wrapped store, and only vectors live in the index.
 *
 * <p>Embedding failures never break memory: a save is kept (just not semantically findable) and a
 * search falls back to the wrapped store's own results.
 */
public final class SemanticMemoryStore implements MemoryStore {

    /** The usual reciprocal-rank-fusion constant: higher values flatten the advantage of top ranks. */
    static final int RRF_K = 60;

    private static final System.Logger LOG = System.getLogger(SemanticMemoryStore.class.getName());

    private final MemoryStore delegate;
    private final SemanticSearch search;

    public SemanticMemoryStore(MemoryStore delegate, SemanticSearch search) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.search = Objects.requireNonNull(search, "search");
    }

    /** The store this one wraps. */
    public MemoryStore delegate() {
        return delegate;
    }

    @Override
    public MemoryEntry save(Scope scope, String content, List<String> tags) {
        MemoryEntry entry = delegate.save(scope, content, tags);
        try {
            search.indexMemory(scope, entry.id(), entry.content(),
                    Map.of("tags", String.join("\n", entry.tags()), "createdAt", entry.createdAt().toString()));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Couldn't index memory " + entry.id() + " for semantic search", e);
        }
        return entry;
    }

    @Override
    public List<MemoryEntry> search(Scope scope, String query, int limit) {
        List<MemoryEntry> keyword = delegate.search(scope, query, limit);
        if (query == null || query.isBlank()) {
            return keyword;
        }
        List<VectorIndex.Match> semantic;
        try {
            // Unrelated entries still come back from a nearest-neighbour search, just with no similarity.
            semantic = search.searchMemory(scope, query, limit).stream().filter(match -> match.score() > 0).toList();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Semantic memory search failed; using keyword results only", e);
            return keyword;
        }

        Map<String, MemoryEntry> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        for (int rank = 0; rank < keyword.size(); rank++) {
            MemoryEntry entry = keyword.get(rank);
            byId.putIfAbsent(entry.id(), entry);
            scores.merge(entry.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < semantic.size(); rank++) {
            VectorIndex.Entry hit = semantic.get(rank).entry();
            byId.putIfAbsent(hit.id(), toMemory(hit));
            scores.merge(hit.id(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        return byId.values().stream()
                .sorted(Comparator.comparingDouble((MemoryEntry entry) -> scores.get(entry.id())).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public boolean delete(Scope scope, String id) {
        boolean deleted = delegate.delete(scope, id);
        try {
            search.removeMemory(scope, id);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Couldn't remove memory " + id + " from the semantic index", e);
        }
        return deleted;
    }

    private static MemoryEntry toMemory(VectorIndex.Entry hit) {
        String tags = hit.metadata().getOrDefault("tags", "");
        String createdAt = hit.metadata().get("createdAt");
        return new MemoryEntry(hit.id(), hit.text(), tags.isEmpty() ? List.of() : Arrays.asList(tags.split("\n")),
                createdAt == null ? Instant.EPOCH : Instant.parse(createdAt));
    }
}
