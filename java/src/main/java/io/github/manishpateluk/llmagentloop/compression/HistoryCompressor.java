package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.provider.Provider;
import com.manishpateluk.llmrouter.model.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compresses a {@link Request} so it fits the context window of the model it's about to be sent
 * to, trying each {@link CompressionMethod} in a preference list in order — the same
 * "first one that works wins" shape {@code llm-router} uses for provider fallback — until the
 * request fits or every method has been exhausted.
 *
 * <p>Model context windows are read from {@code llm-router}'s
 * {@link ModelCapabilityTable}, which is itself a static, class-loaded-once registry — there is
 * intentionally no separate copy of that data kept here, so this class only ever consults the
 * single in-memory table {@code llm-router} already maintains.
 *
 * <p>Every public overload funnels into {@link #compress(Request, Provider, String, List, LlmRouter)};
 * callers only need to supply what they want to override.
 */
public final class HistoryCompressor {

    /** Estimated token counts are inflated by this fraction before being compared to the context window. */
    public static final double CONTEXT_WINDOW_SAFETY_MARGIN = 0.05;

    /** Cheapest/most-local method first; the LLM call is always the last resort. */
    public static final List<CompressionMethod> DEFAULT_METHODS = List.of(
            CompressionMethod.STRUCTURAL_COMPACTION,
            CompressionMethod.EXTRACTIVE_SUMMARIZATION,
            CompressionMethod.LLM_SUMMARIZATION,
            CompressionMethod.SLIDING_WINDOW_TRUNCATION);

    private static final Map<CompressionMethod, CompressionStrategy> STRATEGIES = Map.of(
            CompressionMethod.STRUCTURAL_COMPACTION, new StructuralCompactionStrategy(),
            CompressionMethod.SLIDING_WINDOW_TRUNCATION, new SlidingWindowTruncationStrategy(),
            CompressionMethod.EXTRACTIVE_SUMMARIZATION, new ExtractiveSummarizationStrategy(),
            CompressionMethod.LLM_SUMMARIZATION, new LlmSummarizationStrategy());

    private HistoryCompressor() {
    }

    /** Compresses using {@link #DEFAULT_METHODS} and no LLM fallback available. */
    public static CompressionOutcome compress(Request request, Provider provider, String model) {
        return compress(request, provider, model, DEFAULT_METHODS, null);
    }

    /** Compresses using {@link #DEFAULT_METHODS}, with {@code router} available for {@link CompressionMethod#LLM_SUMMARIZATION}. */
    public static CompressionOutcome compress(Request request, Provider provider, String model, LlmRouter router) {
        return compress(request, provider, model, DEFAULT_METHODS, router);
    }

    /** Compresses using a caller-supplied method preference list, with no LLM fallback available. */
    public static CompressionOutcome compress(
            Request request, Provider provider, String model, List<CompressionMethod> methods) {
        return compress(request, provider, model, methods, null);
    }

    /**
     * Compresses {@code request} so its estimated token count (plus {@link #CONTEXT_WINDOW_SAFETY_MARGIN})
     * fits within {@code model}'s context window, minus room for its max output tokens.
     *
     * @param request the request to compress
     * @param provider provider of the model this request is about to be sent to
     * @param model    model id, looked up in {@link ModelCapabilityTable}
     * @param methods  ordered preference list of {@link CompressionMethod} to try; {@code null} or empty means {@link #DEFAULT_METHODS}
     * @param router   used for {@link CompressionMethod#LLM_SUMMARIZATION}; may be {@code null} if that method isn't in the list
     * @return the (possibly unchanged) request to send, plus a record of what was tried
     * @throws IllegalArgumentException     if {@code provider}/{@code model} isn't in {@link ModelCapabilityTable}
     * @throws CompressionExhaustedException if every method was tried and the request still doesn't fit
     */
    public static CompressionOutcome compress(
            Request request, Provider provider, String model, List<CompressionMethod> methods, LlmRouter router) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");

        List<CompressionMethod> orderedMethods = (methods == null || methods.isEmpty()) ? DEFAULT_METHODS : methods;

        ModelEntry targetModel = ModelCapabilityTable.findModel(provider, model)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown model for compression target: " + provider + "/" + model));

        int targetTokens = Math.max(targetModel.getContextWindowTokens() - targetModel.getMaxOutputTokens(), 0);
        int estimatedTokens = RequestTokenEstimator.estimateTokensWithMargin(request, CONTEXT_WINDOW_SAFETY_MARGIN);

        if (estimatedTokens <= targetTokens) {
            return CompressionOutcome.notNeeded(request, estimatedTokens, targetTokens);
        }

        List<CompressionAttempt> attempts = new ArrayList<>(orderedMethods.size());
        Request current = request;
        int currentTokens = estimatedTokens;

        for (CompressionMethod method : orderedMethods) {
            CompressionStrategy strategy = STRATEGIES.get(method);
            try {
                Request candidate = strategy.compress(current, targetTokens, router);
                int candidateTokens = RequestTokenEstimator.estimateTokensWithMargin(candidate, CONTEXT_WINDOW_SAFETY_MARGIN);

                if (candidateTokens < currentTokens) {
                    attempts.add(CompressionAttempt.success(method, currentTokens, candidateTokens));
                    current = candidate;
                    currentTokens = candidateTokens;
                    if (currentTokens <= targetTokens) {
                        return CompressionOutcome.compressed(current, estimatedTokens, currentTokens, targetTokens, attempts);
                    }
                } else {
                    attempts.add(CompressionAttempt.failure(method, currentTokens, "no token reduction achieved"));
                }
            } catch (RuntimeException e) {
                attempts.add(CompressionAttempt.failure(method, currentTokens, e.getMessage()));
            }
        }

        throw new CompressionExhaustedException(targetTokens, currentTokens, attempts);
    }
}
