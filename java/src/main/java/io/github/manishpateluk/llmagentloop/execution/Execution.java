package io.github.manishpateluk.llmagentloop.execution;

import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of everything that happened during one {@code AgentLoop.run} call — every
 * {@link StepRecord}, across every thread it branched into.
 */
public record Execution(UUID id, List<StepRecord> steps) {

    public Execution {
        steps = List.copyOf(steps);
    }
}
