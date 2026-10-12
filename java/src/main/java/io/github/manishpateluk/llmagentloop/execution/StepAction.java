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

    /** A limit was reached and the model gave its final answer from what the run had gathered, with tools off. */
    WRAP_UP,

    /**
     * The run stopped early without a final answer — cancelled, or a limit was reached and the
     * wrap-up call failed; see {@link TerminationReason}.
     */
    TRUNCATED
}
