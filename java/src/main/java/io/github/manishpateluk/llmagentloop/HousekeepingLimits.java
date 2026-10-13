package io.github.manishpateluk.llmagentloop;

/**
 * Bounds on the model calls a run makes for its own housekeeping rather than for the work itself:
 * the {@code PlanMode#AUTO} plan check, plan generation, the wrap-up answer at a limit, turning the
 * answer into {@link LoopRequest#answerSchema()} data, and summaries (history compression and
 * conversation compaction).
 *
 * <p>Each {@code *Tokens} value caps how many tokens the model may generate for that call
 * (llm-router's {@code Request.maxOutputTokens}; lowered to the model's own maximum if that's
 * smaller, and on reasoning models it includes reasoning tokens). Without a cap a structured call
 * can run to the model's maximum — minutes of output and a cut-off, unusable result. A plan check
 * or plan that fails, is cut off or can't be used only warns: the run carries on step by step,
 * without a plan.
 *
 * @param planCheckTokens        output cap for the plan check
 * @param planTokens             output cap for plan generation
 * @param maxPlanSteps           most steps a plan may have — given to the model in the plan's schema
 *                               ({@code maxItems}); a plan with more is not used
 * @param wrapUpTokens           output cap for the final, tools-off answer when a limit is reached
 * @param answerFormattingTokens output cap for turning the answer into {@code answerSchema} data
 * @param summaryTokens          output cap for summaries: an {@code LLM_SUMMARIZATION} compression
 *                               (which is also held to its compression target) and the default
 *                               conversation compactor
 */
public record HousekeepingLimits(
        int planCheckTokens,
        int planTokens,
        int maxPlanSteps,
        int wrapUpTokens,
        int answerFormattingTokens,
        int summaryTokens) {

    /** Plan check 2,000 tokens, plan 8,000 tokens and 12 steps, wrap-up and answer formatting 16,000, summaries 4,000. */
    public static final HousekeepingLimits DEFAULT = new HousekeepingLimits(2_000, 8_000, 12, 16_000, 16_000, 4_000);

    public HousekeepingLimits {
        requirePositive("planCheckTokens", planCheckTokens);
        requirePositive("planTokens", planTokens);
        requirePositive("maxPlanSteps", maxPlanSteps);
        requirePositive("wrapUpTokens", wrapUpTokens);
        requirePositive("answerFormattingTokens", answerFormattingTokens);
        requirePositive("summaryTokens", summaryTokens);
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }

    public HousekeepingLimits withPlanCheckTokens(int tokens) {
        return new HousekeepingLimits(tokens, planTokens, maxPlanSteps, wrapUpTokens, answerFormattingTokens, summaryTokens);
    }

    public HousekeepingLimits withPlanTokens(int tokens) {
        return new HousekeepingLimits(planCheckTokens, tokens, maxPlanSteps, wrapUpTokens, answerFormattingTokens, summaryTokens);
    }

    public HousekeepingLimits withMaxPlanSteps(int steps) {
        return new HousekeepingLimits(planCheckTokens, planTokens, steps, wrapUpTokens, answerFormattingTokens, summaryTokens);
    }

    public HousekeepingLimits withWrapUpTokens(int tokens) {
        return new HousekeepingLimits(planCheckTokens, planTokens, maxPlanSteps, tokens, answerFormattingTokens, summaryTokens);
    }

    public HousekeepingLimits withAnswerFormattingTokens(int tokens) {
        return new HousekeepingLimits(planCheckTokens, planTokens, maxPlanSteps, wrapUpTokens, tokens, summaryTokens);
    }

    public HousekeepingLimits withSummaryTokens(int tokens) {
        return new HousekeepingLimits(planCheckTokens, planTokens, maxPlanSteps, wrapUpTokens, answerFormattingTokens, tokens);
    }
}
