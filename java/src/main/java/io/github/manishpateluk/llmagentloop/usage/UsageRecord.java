package io.github.manishpateluk.llmagentloop.usage;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;

import java.time.Instant;
import java.util.Map;
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
 * @param inputTokens  every input token, as the provider reported them — including the ones read from
 *                     or written to its prompt cache, which are counted again below
 * @param outputTokens completion tokens, as the provider reported them, reasoning included
 * @param costUsdCents estimated cost from {@code llm-router}'s capability table (approximate), in
 *                     whole cents: small calls such as embeddings usually round to 0
 * @param costUsdMicros the same estimate in micro-dollars (millionths of a dollar): sum this one
 *                     for spend across many calls. Cached input is priced at the cache rates.
 * @param at           when the call completed
 * @param tags         the run's {@code LoopRequest.tags} (who to bill and why), inherited by its delegated runs;
 *                     empty when it has none
 * @param cacheReadTokens  the part of {@code inputTokens} read from the provider's prompt cache (billed at a
 *                     discount); 0 when none, or the provider doesn't say
 * @param cacheWriteTokens the part of {@code inputTokens} written to the prompt cache (billed at a premium by
 *                     the providers that bill writes); 0 when none
 * @param cacheWriteLongTtlTokens the part of {@code cacheWriteTokens} written with the longer cache lifetime
 *                     ({@code RouterConfig.promptCacheTtl LONG}); 0 when none
 * @param reasoningTokens the part of {@code outputTokens} spent on reasoning, where the provider reports it
 */
public record UsageRecord(UUID executionId, Scope scope, UsagePurpose purpose, Provider provider, String model,
                          long inputTokens, long outputTokens, long costUsdCents, long costUsdMicros, Instant at,
                          Map<String, String> tags, long cacheReadTokens, long cacheWriteTokens,
                          long cacheWriteLongTtlTokens, long reasoningTokens) {

    public UsageRecord {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(at, "at");
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    /** A record without cache or reasoning tokens. */
    public UsageRecord(UUID executionId, Scope scope, UsagePurpose purpose, Provider provider, String model,
                       long inputTokens, long outputTokens, long costUsdCents, long costUsdMicros, Instant at,
                       Map<String, String> tags) {
        this(executionId, scope, purpose, provider, model, inputTokens, outputTokens, costUsdCents, costUsdMicros, at,
                tags, 0, 0, 0, 0);
    }

    /** A record without tags, cache or reasoning tokens. */
    public UsageRecord(UUID executionId, Scope scope, UsagePurpose purpose, Provider provider, String model,
                       long inputTokens, long outputTokens, long costUsdCents, long costUsdMicros, Instant at) {
        this(executionId, scope, purpose, provider, model, inputTokens, outputTokens, costUsdCents, costUsdMicros, at,
                Map.of());
    }

    /**
     * The record for one completed model call: every token count and cost {@code response}'s
     * {@link Usage} reports (zeros when it reports none), who served it, and when.
     */
    public static UsageRecord of(UUID executionId, Scope scope, UsagePurpose purpose, Response response,
                                 Map<String, String> tags) {
        Usage usage = response.getUsage() != null ? response.getUsage() : Usage.builder().build();
        return new UsageRecord(executionId, scope, purpose, response.getProviderUsed(), response.getModelUsed(),
                usage.getInputTokens(), usage.getOutputTokens(), usage.getEstimatedCostUsdCents(),
                usage.getEstimatedCostUsdMicros(), Instant.now(), tags, usage.getCacheReadTokens(),
                usage.getCacheWriteTokens(), usage.getCacheWriteLongTtlTokens(), usage.getReasoningTokens());
    }

    /** {@code inputTokens} less what was read from or written to the prompt cache: the input billed at the plain rate. */
    public long uncachedInputTokens() {
        return Math.max(0, inputTokens - cacheReadTokens - cacheWriteTokens);
    }
}
