package io.github.manishpateluk.llmagentloop.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanStepTest {

    @Test
    void nullSuggestedToolsDefaultsToEmptyList() {
        PlanStep step = new PlanStep("do the thing", null, null);

        assertThat(step.suggestedTools()).isEmpty();
    }

    @Test
    void suppliedSuggestedToolsIsCopiedRatherThanReferencingTheOriginalList() {
        List<String> original = new ArrayList<>(List.of("search"));

        PlanStep step = new PlanStep("do the thing", original, null);
        original.add("added after construction");

        assertThat(step.suggestedTools()).containsExactly("search");
    }

    @Test
    void suggestedToolsIsUnmodifiable() {
        PlanStep step = new PlanStep("do the thing", List.of("search"), null);

        assertThatThrownBy(() -> step.suggestedTools().add("nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
