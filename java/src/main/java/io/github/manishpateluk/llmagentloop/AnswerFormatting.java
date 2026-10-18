package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * How a finished answer is turned into {@link LoopRequest#answerSchema()} data: the steps tried, in order, until one
 * gives usable data. Every step converts the same answer text, so the run's work is never redone; only the
 * conversion call is repeated. A step fails when its reply can't be read as data, most often because it was cut off
 * at {@link HousekeepingLimits#answerFormattingTokens()}, or when routing it fails.
 *
 * @param steps the steps to try, in order; at least one
 */
public record AnswerFormatting(List<Step> steps) {

    /** One attempt at the conversion. */
    public enum Step {
        /** On the run's own routing and thinking level. */
        AS_RUN,
        /** On the run's routing at {@code LOW} thinking, sent to the model (its lowest effort, or no thinking). */
        LOW,
        /**
         * At {@code LOW} thinking on the cheapest model of the run's providers that can hold the request: the
         * run's route reduced to provider-only entries (or the default route), with cost-optimized ordering, which
         * skips models whose context window is too small.
         */
        CHEAPER_MODEL
    }

    /** The run's own level, then low thinking, then a cheaper model: the default. */
    public static final AnswerFormatting STEP_DOWN = new AnswerFormatting(List.of(Step.AS_RUN, Step.LOW,
            Step.CHEAPER_MODEL));
    /** Low thinking from the start, then a cheaper model. */
    public static final AnswerFormatting LOW_FIRST = new AnswerFormatting(List.of(Step.LOW, Step.CHEAPER_MODEL));
    /** A cheaper model at low thinking only: the least it can cost. */
    public static final AnswerFormatting CHEAPER_MODEL_ONLY = new AnswerFormatting(List.of(Step.CHEAPER_MODEL));

    public AnswerFormatting {
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("AnswerFormatting needs at least one step");
        }
        steps = List.copyOf(steps);
    }

    /** The routing for {@code step}, from the run's ({@code null}: the default). */
    static RouterConfig configFor(Step step, RouterConfig run) {
        RouterConfig base = run != null ? run : RouterConfig.builder().build();
        return switch (step) {
            case AS_RUN -> run;
            case LOW -> base.toBuilder().thinkingLevel(ThinkingLevel.LOW).sendThinkingLevel(true).build();
            case CHEAPER_MODEL -> base.toBuilder().route(providersOnly(base.getRoute()))
                    .thinkingLevel(ThinkingLevel.LOW).sendThinkingLevel(true).costOptimized(true).build();
        };
    }

    /** The route's providers, in order, without its models; {@code null} (the default route) stays {@code null}. */
    private static List<RouteEntry> providersOnly(List<RouteEntry> route) {
        if (route == null) {
            return null;
        }
        Set<RouteEntry> providers = new LinkedHashSet<>();
        for (RouteEntry entry : route) {
            providers.add(RouteEntry.of(entry.getProvider()));
        }
        return List.copyOf(providers);
    }
}
