package io.github.manishpateluk.llmagentloop.usage;

/**
 * Summed usage over some set of model calls — a run ({@code AgentLoopResult.usage()}), or a tenant
 * or user ({@link InMemoryUsageMeter#totals}).
 */
public record UsageTotals(long calls, long inputTokens, long outputTokens, long costUsdCents) {

    public static final UsageTotals ZERO = new UsageTotals(0, 0, 0, 0);

    public UsageTotals plus(UsageRecord record) {
        return new UsageTotals(calls + 1, inputTokens + record.inputTokens(), outputTokens + record.outputTokens(),
                costUsdCents + record.costUsdCents());
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
