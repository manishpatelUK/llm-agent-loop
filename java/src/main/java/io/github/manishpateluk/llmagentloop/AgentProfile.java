package io.github.manishpateluk.llmagentloop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Builder;

import java.util.List;

/**
 * Describes an agent's overall behavior — goals, constraints, and other fixed attributes — so it
 * can be serialized into the system instructions for every LLM call an {@link AgentLoop} run
 * makes, similar in spirit to Claude "skills".
 *
 * @param planMode         how the run decides whether/how to plan; see {@link PlanMode}. Defaults to {@link PlanMode#AUTO}.
 * @param planningGuidance domain-specific planning tips fed to the planning LLM call, if one happens
 * @param goals            the agent's standing goals — distinct from a single run's prompt
 * @param operatingContext free-text description of where/how this agent operates
 *                         (e.g. "the finance cofounder agent for Acme Inc, operating under UK company law")
 * @param maxSteps         hard cap on total steps taken across a run, all threads combined.
 *                         Any value &le; 0 falls back to {@link #DEFAULT_MAX_STEPS} — there is
 *                         deliberately no way to request "unbounded".
 */
@Builder
public record AgentProfile(
        PlanMode planMode,
        List<String> planningGuidance,
        List<String> goals,
        String operatingContext,
        int maxSteps) {

    /** Used when a run's {@link LoopRequest#agentProfile()} is {@code null}. */
    public static final AgentProfile DEFAULT = AgentProfile.builder().build();

    /** The step-count safety net until real cost/time budgets exist. */
    public static final int DEFAULT_MAX_STEPS = 25;

    private static final ObjectMapper JSON = new ObjectMapper();

    public AgentProfile {
        planMode = planMode == null ? PlanMode.AUTO : planMode;
        planningGuidance = planningGuidance == null ? List.of() : List.copyOf(planningGuidance);
        goals = goals == null ? List.of() : List.copyOf(goals);
        maxSteps = maxSteps <= 0 ? DEFAULT_MAX_STEPS : maxSteps;
    }

    /** Renders this profile as a JSON fragment to fold into system instructions. */
    public String toSystemInstructionsFragment() {
        try {
            return "Agent operating profile: " + JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize AgentProfile to JSON", e);
        }
    }
}
