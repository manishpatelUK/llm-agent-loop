package io.github.manishpateluk.llmagentloop.execution;

/** Why an {@link Execution} stopped. */
public enum TerminationReason {

    /** The goal was declared complete in the normal way. */
    COMPLETED,

    /** Stopped early: {@code LoopRequest.maxCostUsdCents} was met or exceeded. */
    COST_LIMIT_REACHED,

    /** Stopped early: {@code LoopRequest.maxDuration} was met or exceeded. */
    TIME_LIMIT_REACHED
}
