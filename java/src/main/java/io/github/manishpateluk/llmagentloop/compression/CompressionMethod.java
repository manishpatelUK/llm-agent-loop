package io.github.manishpateluk.llmagentloop.compression;

/**
 * Named, locally-runnable (or LLM-backed) history compression strategies that can be placed in a
 * {@link HistoryCompressor} preference list, ordered cheapest/most-local first by convention.
 */
public enum CompressionMethod {

    /** Deduplicates repeated tool output and normalizes whitespace. Free, no token cost. */
    STRUCTURAL_COMPACTION,

    /** Drops the oldest non-pinned history turns until the target token budget is met. */
    SLIDING_WINDOW_TRUNCATION,

    /** Hand-rolled TextRank sentence extraction ({@link TextRankSummarizer}); no model to load. */
    EXTRACTIVE_SUMMARIZATION,

    /** Summarizes older history via an LLM call through {@code llm-router}. */
    LLM_SUMMARIZATION
}
