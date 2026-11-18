package io.github.manishpateluk.llmagentloop.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link RecallPolicy} decides from, once per model call of a working step.
 *
 * @param memory   the run's memory, already bound to its scope
 * @param goal     the goal the step is working on (a sub-task's own goal inside a sub-task)
 * @param step     how many recalls this run has made before this one (0 on its first step)
 * @param previous what the policy returned last time in this run; {@code null} on the first step
 * @param tags     the run's {@code LoopRequest.tags}; never {@code null}
 */
public record RecallRequest(ScopedMemory memory, String goal, int step, List<MemoryEntry> previous,
        Map<String, String> tags) {

    public RecallRequest {
        Objects.requireNonNull(memory, "memory");
        goal = goal == null ? "" : goal;
        previous = previous == null ? null : List.copyOf(previous);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
}
