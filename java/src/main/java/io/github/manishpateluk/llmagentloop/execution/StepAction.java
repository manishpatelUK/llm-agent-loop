package io.github.manishpateluk.llmagentloop.execution;

/** What a single recorded step in an {@link Execution} actually did. */
public enum StepAction {

    /** A cheap check deciding whether an explicit plan is needed ({@code PlanMode#AUTO} only). */
    PLAN_CHECK,

    /** An explicit {@code Plan} was generated. */
    PLAN_CREATED,

    /** A tool (registered or otherwise) was called. */
    TOOL_CALL,

    /** A sub-task was delegated to a new thread. */
    SUB_TASK,

    /**
     * A compression-enabled router compressed (or failed to compress) this thread's next call to
     * fit its target model; the step's {@code description} says which. No {@code response} — it
     * happened inside the call, before anything was sent.
     */
    HISTORY_COMPRESSION,

    /** The thread's goal was declared complete. */
    COMPLETE,

    /** The run stopped early — a cost or time bound was reached; see {@link TerminationReason}. */
    TRUNCATED
}
