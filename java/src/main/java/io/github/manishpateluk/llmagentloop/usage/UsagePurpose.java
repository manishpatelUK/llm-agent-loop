package io.github.manishpateluk.llmagentloop.usage;

/** What a metered model call was for. */
public enum UsagePurpose {

    /** A working step: the model deciding what to do next, calling tools, or answering. */
    STEP,

    /** {@code PlanMode.AUTO}'s cheap check of whether a request needs a plan. */
    PLAN_CHECK,

    /** Generating an explicit plan. */
    PLAN,

    /** Turning the final answer into structured data ({@code LoopRequest.answerSchema}). */
    ANSWER_FORMATTING,

    /** History compression's LLM summarization tier, run to fit a request into a model's context window. */
    HISTORY_COMPRESSION,

    /** Summarizing a long chat session's older turns in the conversation store. */
    CONVERSATION_COMPACTION,

    /** Embedding text for semantic search: indexing workspace files and memories, and embedding search queries. */
    EMBEDDING
}
