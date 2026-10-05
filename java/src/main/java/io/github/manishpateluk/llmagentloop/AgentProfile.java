package io.github.manishpateluk.llmagentloop;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.manishpateluk.llmrouter.config.RouterConfig;
import lombok.Builder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

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
 *                         (e.g. "the finance assistant for Acme Inc, operating under UK company law")
 * @param maxSteps         hard cap on total steps taken across a run, all threads combined.
 *                         Any value &le; 0 falls back to {@link #DEFAULT_MAX_STEPS} — there is
 *                         deliberately no way to request "unbounded".
 * @param instructions     free-form Markdown describing how the agent behaves — typically an
 *                         {@code Agent}'s definition plus its skills' guidance. Placed verbatim at
 *                         the top of the system instructions, ahead of the structured profile.
 * @param routerConfig     how this agent's model calls are routed by default — e.g. a cheap model for a
 *                         chat agent, a strong one for drafting contracts, or a provider pinned per
 *                         tenant. {@code LoopRequest.routerConfig} overrides it per run. Not shown to the model.
 */
@Builder
public record AgentProfile(
        PlanMode planMode,
        List<String> planningGuidance,
        List<String> goals,
        String operatingContext,
        int maxSteps,
        @JsonIgnore String instructions,
        @JsonIgnore RouterConfig routerConfig) {

    /** Used when a run's {@link LoopRequest#agentProfile()} is {@code null}. */
    public static final AgentProfile DEFAULT = AgentProfile.builder().build();

    /** The step-count safety net until real cost/time budgets exist. */
    public static final int DEFAULT_MAX_STEPS = 25;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public AgentProfile {
        planMode = planMode == null ? PlanMode.AUTO : planMode;
        planningGuidance = planningGuidance == null ? List.of() : List.copyOf(planningGuidance);
        goals = goals == null ? List.of() : List.copyOf(goals);
        maxSteps = maxSteps <= 0 ? DEFAULT_MAX_STEPS : maxSteps;
    }

    /** Renders this profile for the system instructions: {@link #instructions()} verbatim, then the rest as JSON. */
    public String toSystemInstructionsFragment() {
        String profile;
        try {
            profile = "Agent operating profile: " + JSON.writeValueAsString(this);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize AgentProfile to JSON", e);
        }
        return instructions == null || instructions.isBlank() ? profile : instructions.strip() + "\n\n" + profile;
    }
}
