package io.github.manishpateluk.llmagentloop.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    @Test
    void stepsIsCopiedRatherThanReferencingTheOriginalList() {
        PlanStep step = new PlanStep("do the thing", null, null);
        List<PlanStep> original = new ArrayList<>(List.of(step));

        Plan plan = new Plan(original);
        original.add(new PlanStep("added after construction", null, null));

        assertThat(plan.steps()).containsExactly(step);
    }

    @Test
    void stepsIsUnmodifiable() {
        Plan plan = new Plan(List.of(new PlanStep("do the thing", null, null)));

        assertThatThrownBy(() -> plan.steps().add(new PlanStep("nope", null, null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
