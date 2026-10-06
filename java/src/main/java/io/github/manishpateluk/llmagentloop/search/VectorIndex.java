package io.github.manishpateluk.llmagentloop.search;

import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stores embedding vectors and finds the nearest ones, partitioned by collection (e.g.
 * {@code "workspace"}, {@code "memory"}) and {@link Scope}. {@link InMemoryVectorIndex} is the
 * ready-made option for development; implement this over pgvector, Pinecone, Weaviate, OpenSearch,
 * etc. for production.
 *
 * <p>As with the other stores, {@code scope} arrives already reduced to the configured level — use
 * {@code scope.key()} as the partition key. Each entry belongs to a {@code source} (a file path, a
 * memory id) at a {@code version}; re-indexing a source replaces all its entries. Only entries made
 * by the same embedding model as the query are compared. Must be safe for concurrent use.
 */
public interface VectorIndex {

    /** Adds or replaces {@code entries} (matched by {@link Entry#id()}). */
    void upsert(String collection, Scope scope, List<Entry> entries);

    /** Removes every entry belonging to {@code source}. */
    void deleteSource(String collection, Scope scope, String source);

    /** Up to {@code limit} entries made with {@code model} nearest to {@code query}, most similar first. */
    List<Match> search(String collection, Scope scope, float[] query, String model, int limit);

    /** Each indexed source's version — so callers can tell what's stale and re-index only that. */
    Map<String, String> sourceVersions(String collection, Scope scope);

    /**
     * One indexed piece of text.
     *
     * @param id       unique within the collection and scope, e.g. {@code "reports/q3.pdf#4"}
     * @param source   what it came from (a workspace path, a memory id)
     * @param version  the source's version when indexed (e.g. the file's modification time)
     * @param text     the text that was embedded — returned with matches
     * @param vector   its embedding
     * @param model    which embedding model made the vector
     * @param metadata anything else worth returning with a match (e.g. character offsets)
     */
    record Entry(String id, String source, String version, String text, float[] vector, String model,
                 Map<String, String> metadata) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(vector, "vector");
            Objects.requireNonNull(model, "model");
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /** A search hit; {@code score} is cosine similarity, from -1 to 1. */
    record Match(Entry entry, double score) {
    }
}
