package io.github.manishpateluk.llmagentloop;

/**
 * How an {@link AgentLoop} run decides whether, and how, to plan before executing steps.
 */
public enum PlanMode {

    /**
     * Bypasses the agent loop entirely: a single {@code llm-router} call, and its response is
     * the result. No tool-calling, no multi-step tracking. For requests that are genuinely
     * one-shot (e.g. a plain chatbot reply) where the overhead of the full loop isn't warranted.
     */
    NEVER_PLAN,

    /** Generates an explicit {@code Plan} up front via one LLM call, then executes its steps in order. */
    ALWAYS_PLAN,

    /**
     * No upfront plan. Each step decides its own next action, and a step may itself spawn a
     * sub-task — a nested thread that runs the same step loop against a sub-goal, folding its
     * result back in as context once it completes.
     */
    RECURSIVE_ON_EACH_STEP,

    /**
     * A cheap runtime check decides between {@link #ALWAYS_PLAN} and {@link #RECURSIVE_ON_EACH_STEP}
     * behavior for this specific request. Never resolves to {@link #NEVER_PLAN} — that's a
     * deliberate low-overhead opt-in a profile author chooses, not something to infer.
     */
    AUTO
}
