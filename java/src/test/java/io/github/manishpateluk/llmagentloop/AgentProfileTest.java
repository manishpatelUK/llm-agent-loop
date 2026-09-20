package io.github.manishpateluk.llmagentloop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProfileTest {

    @Test
    void planModeDefaultsToAuto() {
        assertThat(AgentProfile.builder().build().planMode()).isEqualTo(PlanMode.AUTO);
    }

    @Test
    void goalsAndPlanningGuidanceDefaultToEmptyRatherThanNull() {
        AgentProfile profile = AgentProfile.builder().build();

        assertThat(profile.goals()).isEmpty();
        assertThat(profile.planningGuidance()).isEmpty();
    }

    @Test
    void maxStepsFallsBackToDefaultWhenUnsetOrNonPositive() {
        assertThat(AgentProfile.builder().build().maxSteps()).isEqualTo(AgentProfile.DEFAULT_MAX_STEPS);
        assertThat(AgentProfile.builder().maxSteps(0).build().maxSteps()).isEqualTo(AgentProfile.DEFAULT_MAX_STEPS);
        assertThat(AgentProfile.builder().maxSteps(-5).build().maxSteps()).isEqualTo(AgentProfile.DEFAULT_MAX_STEPS);
    }

    @Test
    void maxStepsKeepsAnExplicitPositiveValue() {
        assertThat(AgentProfile.builder().maxSteps(3).build().maxSteps()).isEqualTo(3);
    }

    @Test
    void toSystemInstructionsFragmentIncludesGoalsAndContext() {
        AgentProfile profile = AgentProfile.builder()
                .goals(List.of("Ship the feature"))
                .operatingContext("A small startup")
                .build();

        String fragment = profile.toSystemInstructionsFragment();

        assertThat(fragment).contains("Ship the feature").contains("A small startup");
    }

    @Test
    void defaultConstantIsUsable() {
        assertThat(AgentProfile.DEFAULT.planMode()).isEqualTo(PlanMode.AUTO);
        assertThat(AgentProfile.DEFAULT.maxSteps()).isEqualTo(AgentProfile.DEFAULT_MAX_STEPS);
    }
}
