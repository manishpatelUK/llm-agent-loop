package io.github.manishpateluk.llmagentloop.execution;

/** Why an {@link Execution} stopped. */
public enum TerminationReason {

    /** The goal was declared complete in the normal way. */
    COMPLETED,

    /**
     * Stopped at {@code AgentProfile.maxSteps}. Like the other {@code *_LIMIT_REACHED} reasons, the
     * final response is a wrap-up answer from what the run gathered (see {@code WindDown}) — or, if
     * that last call failed, the run's last response.
     */
    STEP_LIMIT_REACHED,

    /** Stopped at {@code LoopRequest.maxCostUsdCents}; see {@link #STEP_LIMIT_REACHED}. */
    COST_LIMIT_REACHED,

    /** Stopped at {@code LoopRequest.maxDuration}; see {@link #STEP_LIMIT_REACHED}. */
    TIME_LIMIT_REACHED,

    /** Stopped early: the caller cancelled the run ({@code RunHandle.cancel()}, or by interrupting a {@code runAndWait} thread). */
    CANCELLED
}
