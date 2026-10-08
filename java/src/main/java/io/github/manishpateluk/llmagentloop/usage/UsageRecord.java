package io.github.manishpateluk.llmagentloop.usage;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmrouter.provider.Provider;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One metered model call.
 *
 * @param executionId  the run that made it; {@code null} for calls outside a run (e.g. background
 *                     indexing, or semantic search called from your own code outside a run)
 * @param scope        the run's full scope — whose usage this is (tenant, user, session)
 * @param purpose      what the call was for
 * @param provider     which provider served it; {@code null} if unknown
 * @param model        which model served it; {@code null} if unknown
 * @param inputTokens  prompt tokens, as the provider reported them
 * @param outputTokens completion tokens, as the provider reported them
 * @param costUsdCents estimated cost from {@code llm-router}'s capability table (approximate), in
 *                     whole cents: small calls such as embeddings usually round to 0
 * @param costUsdMicros the same estimate in micro-dollars (millionths of a dollar): sum this one
 *                     for spend across many calls
 * @param at           when the call completed
 */
public record UsageRecord(UUID executionId, Scope scope, UsagePurpose purpose, Provider provider, String model,
                          long inputTokens, long outputTokens, long costUsdCents, long costUsdMicros, Instant at) {

    public UsageRecord {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(at, "at");
    }
}
