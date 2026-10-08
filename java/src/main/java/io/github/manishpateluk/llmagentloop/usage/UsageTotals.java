package io.github.manishpateluk.llmagentloop.usage;

/**
 * Summed usage over some set of model calls — a run ({@code AgentLoopResult.usage()}), or a tenant
 * or user ({@link InMemoryUsageMeter#totals}).
 *
 * <p>{@code costUsdMicros} (millionths of a dollar) is the accurate total. {@code costUsdCents} is
 * the sum of each call's whole-cent estimate, so it undercounts many small calls (an embedding call
 * usually rounds to 0 cents); use {@link #costUsdCentsRounded()} for a cents figure.
 */
public record UsageTotals(long calls, long inputTokens, long outputTokens, long costUsdCents, long costUsdMicros) {

    public static final UsageTotals ZERO = new UsageTotals(0, 0, 0, 0, 0);

    public UsageTotals plus(UsageRecord record) {
        return new UsageTotals(calls + 1, inputTokens + record.inputTokens(), outputTokens + record.outputTokens(),
                costUsdCents + record.costUsdCents(), costUsdMicros + record.costUsdMicros());
    }

    /** These totals and {@code other}'s, added together. */
    public UsageTotals plus(UsageTotals other) {
        return new UsageTotals(calls + other.calls, inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                costUsdCents + other.costUsdCents, costUsdMicros + other.costUsdMicros);
    }

    /** {@link #costUsdMicros()} rounded to the nearest cent. */
    public long costUsdCentsRounded() {
        return Math.round(costUsdMicros / 10_000.0);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
