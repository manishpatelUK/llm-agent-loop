package io.github.manishpateluk.llmagentloop.usage;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.ScopeLevel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link UsageMeter}: running totals in this process's heap, per tenant and per user
 * (not per session, so memory stays bounded by your number of users). Lost on restart — for
 * dashboards and soft limits within one process; use your own meter to persist usage for billing.
 *
 * <pre>{@code
 * InMemoryUsageMeter meter = (InMemoryUsageMeter) loop.usageMeter();
 * UsageTotals thisMonth = meter.totals(Scope.of(tenantId, userId, sessionId).atLevel(ScopeLevel.USER));
 * }</pre>
 */
public final class InMemoryUsageMeter implements UsageMeter {

    private final Map<Scope, UsageTotals> totals = new ConcurrentHashMap<>();

    @Override
    public void record(UsageRecord record) {
        totals.merge(record.scope().atLevel(ScopeLevel.TENANT), UsageTotals.ZERO.plus(record), InMemoryUsageMeter::sum);
        totals.merge(record.scope().atLevel(ScopeLevel.USER), UsageTotals.ZERO.plus(record), InMemoryUsageMeter::sum);
    }

    /**
     * Totals for a tenant ({@code scope.atLevel(ScopeLevel.TENANT)}) or a user
     * ({@code scope.atLevel(ScopeLevel.USER)}); a full session scope is reduced to its user.
     */
    public UsageTotals totals(Scope scope) {
        Scope key = scope.sessionId() != null ? scope.atLevel(ScopeLevel.USER) : scope;
        return totals.getOrDefault(key, UsageTotals.ZERO);
    }

    /** Forgets everything recorded so far, e.g. at the start of a billing period. */
    public void reset() {
        totals.clear();
    }

    private static UsageTotals sum(UsageTotals a, UsageTotals b) {
        return new UsageTotals(a.calls() + b.calls(), a.inputTokens() + b.inputTokens(),
                a.outputTokens() + b.outputTokens(), a.costUsdCents() + b.costUsdCents());
    }
}
