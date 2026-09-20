package io.github.manishpateluk.llmagentloop;

/**
 * Thrown when a run takes more steps (across all threads combined) than
 * {@link AgentProfile#maxSteps()} allows — the safety net against unbounded recursion until real
 * cost/time budgets exist.
 */
public class AgentLoopStepLimitExceededException extends RuntimeException {

    AgentLoopStepLimitExceededException(int maxSteps) {
        super("Exceeded the maximum of " + maxSteps + " step(s) for this run.");
    }
}
