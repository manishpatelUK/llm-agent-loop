package io.github.manishpateluk.llmagentloop.search;

import java.util.List;
import java.util.Map;

/**
 * Semantic search over the current run's workspace files — what a tool reaches through
 * {@code ToolContext.knowledge()}, already bound to the run's scope. Backs {@code knowledge_search}.
 */
@FunctionalInterface
public interface KnowledgeSearch {

    /** Not configured: searching fails with a pointer to {@code AgentLoop.builder().semanticSearch(...)}. */
    KnowledgeSearch NONE = (query, limit) -> {
        throw new IllegalStateException(
                "Semantic search isn't configured: pass AgentLoop.builder().semanticSearch(SemanticSearch.builder().build()) "
                        + "to use knowledge_search");
    };

    /** Up to {@code limit} passages most relevant to {@code query}, best first. */
    List<Hit> search(String query, int limit);

    /**
     * A matching passage.
     *
     * @param source   the workspace path it came from
     * @param text     the passage
     * @param score    cosine similarity, from -1 to 1
     * @param metadata e.g. {@code start}, the passage's character offset in the file's text
     */
    record Hit(String source, String text, double score, Map<String, String> metadata) {
        public Hit {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
