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

    /** The thread's goal was declared complete. */
    COMPLETE
}
