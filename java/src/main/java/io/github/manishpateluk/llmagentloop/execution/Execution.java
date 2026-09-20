package io.github.manishpateluk.llmagentloop.execution;

import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of everything that happened during one {@code AgentLoop.run} call — every
 * {@link StepRecord}, across every thread it branched into, and why it stopped.
 */
public record Execution(UUID id, List<StepRecord> steps, TerminationReason terminationReason) {

    public Execution {
        steps = List.copyOf(steps);
        terminationReason = terminationReason == null ? TerminationReason.COMPLETED : terminationReason;
    }
}
