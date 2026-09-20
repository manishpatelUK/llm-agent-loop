package io.github.manishpateluk.llmagentloop.plan;

import java.util.List;

/**
 * One step of a {@link Plan}.
 *
 * @param description    what this step should accomplish
 * @param suggestedTools tool names the planning call thinks are relevant; not enforced — the
 *                       step is still free to call any registered tool
 * @param parallelGroup  steps sharing a non-null group are conceptually parallel branches;
 *                       execution is sequential for now, but the grouping is preserved for when it isn't
 */
public record PlanStep(String description, List<String> suggestedTools, Integer parallelGroup) {

    public PlanStep {
        suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
    }
}
