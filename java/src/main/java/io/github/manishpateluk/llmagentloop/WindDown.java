package io.github.manishpateluk.llmagentloop;

import java.time.Duration;
import java.util.Objects;

/**
 * How a run winds down as it nears its limits ({@link AgentProfile#maxSteps()},
 * {@link LoopRequest#maxCostUsdCents()}, {@link LoopRequest#maxDuration()}).
 *
 * <p>Close to a limit, the next working model call gets a short note saying which limit, how much
 * is left, and to stop gathering and answer from what it has — once per limit. When a limit is
 * reached, the run makes one last model call with tools off, asking for the best complete answer
 * from everything gathered so far, and ends with that answer and a {@code *_LIMIT_REACHED}
 * termination reason.
 *
 * @param warnAtStepsLeft    warn when this many steps are left; {@code 0} never warns about steps
 * @param warnAtCostFraction warn once this fraction of the cost limit is spent, in {@code (0, 1]}
 * @param warnAtTimeFraction warn once this fraction of the time limit has passed, in {@code (0, 1]}
 * @param wrapUpTimeout      how long the final, tools-off call may take — on top of a time limit
 *                           that's already been reached. If it runs over or fails, the run ends with
 *                           its last response instead.
 */
public record WindDown(int warnAtStepsLeft, double warnAtCostFraction, double warnAtTimeFraction, Duration wrapUpTimeout) {

    /** Warn with 2 steps left, or at 80% of the cost or time limit; 60 s for the final answer. */
    public static final WindDown DEFAULT = new WindDown(2, 0.8, 0.8, Duration.ofSeconds(60));

    public WindDown {
        if (warnAtStepsLeft < 0) {
            throw new IllegalArgumentException("warnAtStepsLeft must not be negative, was " + warnAtStepsLeft);
        }
        if (!(warnAtCostFraction > 0 && warnAtCostFraction <= 1) || !(warnAtTimeFraction > 0 && warnAtTimeFraction <= 1)) {
            throw new IllegalArgumentException("warning fractions must be in (0, 1], were "
                    + warnAtCostFraction + " and " + warnAtTimeFraction);
        }
        Objects.requireNonNull(wrapUpTimeout, "wrapUpTimeout");
        if (wrapUpTimeout.isZero() || wrapUpTimeout.isNegative()) {
            throw new IllegalArgumentException("wrapUpTimeout must be positive, was " + wrapUpTimeout);
        }
    }

    public WindDown withWarnAtStepsLeft(int steps) {
        return new WindDown(steps, warnAtCostFraction, warnAtTimeFraction, wrapUpTimeout);
    }

    public WindDown withWarnAtCostFraction(double fraction) {
        return new WindDown(warnAtStepsLeft, fraction, warnAtTimeFraction, wrapUpTimeout);
    }

    public WindDown withWarnAtTimeFraction(double fraction) {
        return new WindDown(warnAtStepsLeft, warnAtCostFraction, fraction, wrapUpTimeout);
    }

    public WindDown withWrapUpTimeout(Duration timeout) {
        return new WindDown(warnAtStepsLeft, warnAtCostFraction, warnAtTimeFraction, timeout);
    }
}
