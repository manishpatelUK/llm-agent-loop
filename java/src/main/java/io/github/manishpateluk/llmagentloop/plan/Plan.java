package io.github.manishpateluk.llmagentloop.plan;

import java.util.List;

/** An ordered sequence of steps an {@link io.github.manishpateluk.llmagentloop.AgentLoop} run should follow. */
public record Plan(List<PlanStep> steps) {

    public Plan {
        steps = List.copyOf(steps);
    }
}
