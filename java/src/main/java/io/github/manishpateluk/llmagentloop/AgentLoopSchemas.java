package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * JSON-schema and tool-definition constants {@link AgentLoop} uses for its own internal LLM
 * calls — the "repertoire of structured output schemas" so a step's decision, or the up-front
 * plan-needed check, never comes back as loose prose.
 */
final class AgentLoopSchemas {

    private AgentLoopSchemas() {
    }

    static final String REPORT_COMPLETE_TOOL = "report_complete";
    static final String SPAWN_SUB_TASK_TOOL = "spawn_sub_task";

    /** A synthetic "tool" a step calls to signal its goal is fully achieved. */
    static final ToolDefinition REPORT_COMPLETE = ToolDefinition.builder()
            .name(REPORT_COMPLETE_TOOL)
            .description("Call this when the goal has been fully achieved, with the final answer to give back.")
            .parameters(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "finalAnswer", Map.of(
                                    "type", "string",
                                    "description", "The complete final answer.")),
                    "required", List.of("finalAnswer")))
            .build();

    /** A synthetic "tool" a step calls to delegate a self-contained sub-goal to its own reasoning thread. */
    static final ToolDefinition SPAWN_SUB_TASK = ToolDefinition.builder()
            .name(SPAWN_SUB_TASK_TOOL)
            .description("Call this to delegate a self-contained sub-goal to its own reasoning thread, "
                    + "whose result will be given back to you once it finishes.")
            .parameters(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "goal", Map.of(
                                    "type", "string",
                                    "description", "The sub-goal to accomplish.")),
                    "required", List.of("goal")))
            .build();

    /** Longest one-sentence summary or reason the schemas ask for. */
    static final int MAX_SUMMARY_CHARS = 300;

    /** Longest plan step description the plan schema asks for. */
    static final int MAX_STEP_DESCRIPTION_CHARS = 500;

    /** Structured-output schema for the cheap {@code PlanMode#AUTO} plan-needed check. */
    static final Map<String, Object> PLAN_NEEDED_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "needsPlan", Map.of(
                            "type", "boolean",
                            "description", "Whether accomplishing the goal requires an explicit multi-step plan."),
                    "reason", Map.of(
                            "type", "string",
                            "maxLength", MAX_SUMMARY_CHARS,
                            "description", "One sentence explaining the decision — shown to the caller as a status update.")),
            "required", List.of("needsPlan", "reason"));

    /** Structured-output schema for generating a {@code Plan} of at most {@code HousekeepingLimits.DEFAULT.maxPlanSteps()} steps. */
    static final Map<String, Object> PLAN_SCHEMA = planSchema(HousekeepingLimits.DEFAULT.maxPlanSteps());

    /**
     * Structured-output schema for generating a {@code Plan} of 1 to {@code maxSteps} steps. The
     * bounds keep constrained decoding from producing an unbounded plan; providers that can't
     * enforce {@code maxItems}/{@code maxLength} (Anthropic) get them as description text instead.
     */
    static Map<String, Object> planSchema(int maxSteps) {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "summary", Map.of(
                                "type", "string",
                                "maxLength", MAX_SUMMARY_CHARS,
                                "description", "One sentence describing the plan — shown to the caller as a status update."),
                        "steps", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "maxItems", maxSteps,
                                "description", "The steps, in order: at most " + maxSteps + ", each one a substantial piece of work.",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "description", Map.of("type", "string", "maxLength", MAX_STEP_DESCRIPTION_CHARS),
                                                "suggestedTools", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "string")),
                                                "parallelGroup", Map.of("type", List.of("integer", "null"))),
                                        "required", List.of("description")))),
                "required", List.of("summary", "steps"));
    }
}
