package io.github.manishpateluk.llmagentloop.memory;

import java.util.List;

/**
 * Which memories a run is reminded of, unprompted, before each of its model calls (they're added to that call's
 * prompt under "Relevant memory:"). Set with {@code AgentLoop.Builder.recall}; runs started from a run (sub-tasks,
 * delegated agents on the same loop) use the same policy.
 *
 * <p>A policy is shared by every run of a loop, so it keeps no state of its own: what a run recalled before is in
 * {@link RecallRequest#previous()}.
 */
@FunctionalInterface
public interface RecallPolicy {

    /** The memories to show this step; empty for none. Never {@code null}. */
    List<MemoryEntry> recall(RecallRequest request);

    /** Recall nothing unprompted (the model can still call {@code memory_search}). */
    RecallPolicy NONE = request -> List.of();

    /** The default: search memory with the current goal before every step, showing up to {@code limit}. */
    static RecallPolicy everyStep(int limit) {
        requirePositive(limit);
        return request -> request.memory().search(request.goal(), limit);
    }

    /**
     * Search memory once, on the run's first step, and show the same memories on every later step: one search per
     * run, where {@link #everyStep} makes one per step (and, with semantic search, one embedding call each).
     */
    static RecallPolicy oncePerRun(int limit) {
        requirePositive(limit);
        return request -> request.previous() != null ? request.previous()
                : request.memory().search(request.goal(), limit);
    }

    private static void requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
    }
}
