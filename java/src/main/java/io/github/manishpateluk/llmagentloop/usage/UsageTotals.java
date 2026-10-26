package io.github.manishpateluk.llmagentloop.usage;

/**
 * Summed usage over some set of model calls — a run ({@code AgentLoopResult.usage()}), or a tenant
 * or user ({@link InMemoryUsageMeter#totals}).
 *
 * <p>{@code costUsdMicros} (millionths of a dollar) is the accurate total. {@code costUsdCents} is
 * the sum of each call's whole-cent estimate, so it undercounts many small calls (an embedding call
 * usually rounds to 0 cents); use {@link #costUsdCentsRounded()} for a cents figure.
 *
 * <p>{@code cacheReadTokens}, {@code cacheWriteTokens} and {@code cacheWriteLongTtlTokens} are parts of
 * {@code inputTokens}, and {@code reasoningTokens} part of {@code outputTokens}, as on {@link UsageRecord}.
 * {@link #cacheReadFraction()} says how much of the input came from the providers' prompt caches.
 */
public record UsageTotals(long calls, long inputTokens, long outputTokens, long costUsdCents, long costUsdMicros,
                          long cacheReadTokens, long cacheWriteTokens, long cacheWriteLongTtlTokens,
                          long reasoningTokens) {

    public static final UsageTotals ZERO = new UsageTotals(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Totals without cache or reasoning tokens. */
    public UsageTotals(long calls, long inputTokens, long outputTokens, long costUsdCents, long costUsdMicros) {
        this(calls, inputTokens, outputTokens, costUsdCents, costUsdMicros, 0, 0, 0, 0);
    }

    public UsageTotals plus(UsageRecord record) {
        return new UsageTotals(calls + 1, inputTokens + record.inputTokens(), outputTokens + record.outputTokens(),
                costUsdCents + record.costUsdCents(), costUsdMicros + record.costUsdMicros(),
                cacheReadTokens + record.cacheReadTokens(), cacheWriteTokens + record.cacheWriteTokens(),
                cacheWriteLongTtlTokens + record.cacheWriteLongTtlTokens(), reasoningTokens + record.reasoningTokens());
    }

    /** These totals and {@code other}'s, added together. */
    public UsageTotals plus(UsageTotals other) {
        return new UsageTotals(calls + other.calls, inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                costUsdCents + other.costUsdCents, costUsdMicros + other.costUsdMicros,
                cacheReadTokens + other.cacheReadTokens, cacheWriteTokens + other.cacheWriteTokens,
                cacheWriteLongTtlTokens + other.cacheWriteLongTtlTokens, reasoningTokens + other.reasoningTokens);
    }

    /** {@link #costUsdMicros()} rounded to the nearest cent. */
    public long costUsdCentsRounded() {
        return Math.round(costUsdMicros / 10_000.0);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    /** Input billed at the plain rate: {@code inputTokens} less what was read from or written to a prompt cache. */
    public long uncachedInputTokens() {
        return Math.max(0, inputTokens - cacheReadTokens - cacheWriteTokens);
    }

    /** The share of {@code inputTokens} read from a prompt cache, 0 to 1; 0 with no input. */
    public double cacheReadFraction() {
        return inputTokens == 0 ? 0 : (double) cacheReadTokens / inputTokens;
    }
}
