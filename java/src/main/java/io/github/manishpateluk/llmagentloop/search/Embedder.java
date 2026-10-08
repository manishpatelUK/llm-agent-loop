package io.github.manishpateluk.llmagentloop.search;

import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.model.EmbeddingRequest;
import io.github.manishpateluk.llmrouter.model.EmbeddingResponse;
import io.github.manishpateluk.llmrouter.provider.Provider;

import java.util.List;
import java.util.Objects;

/**
 * Turns text into vectors for semantic search. {@link #router} uses {@code llm-router}'s
 * embeddings (OpenAI's {@code text-embedding-3-small} by default); implement this to use anything
 * else — another embeddings API, or a local model.
 *
 * <p>Vectors from different models can't be compared, so {@link Embeddings#model()} is stored with
 * every indexed vector and searches only compare like with like.
 */
@FunctionalInterface
public interface Embedder {

    /** One vector per text, in order. */
    Embeddings embed(List<String> texts);

    /**
     * @param vectors     one per input text, in order
     * @param model       which model produced them, e.g. {@code openai/text-embedding-3-small}
     * @param provider    who served them, if known
     * @param inputTokens tokens consumed, for metering
     * @param costUsdMicros what the call cost in micro-dollars (millionths of a dollar), for metering;
     *                      0 if unknown
     */
    record Embeddings(List<float[]> vectors, String model, Provider provider, long inputTokens, long costUsdMicros) {
        public Embeddings {
            vectors = List.copyOf(Objects.requireNonNull(vectors, "vectors"));
            Objects.requireNonNull(model, "model");
        }

        /** Embeddings whose cost isn't known (metered as 0). */
        public Embeddings(List<float[]> vectors, String model, Provider provider, long inputTokens) {
            this(vectors, model, provider, inputTokens, 0);
        }
    }

    /**
     * Embeds via {@code router}, with its first available embedding provider's default model. Cost
     * comes from {@code llm-router}'s embedding prices (0 for a model it doesn't price).
     */
    static Embedder router(LlmRouter router) {
        return router(router, null);
    }

    /** Embeds via {@code router} with a fixed model, e.g. {@code RouteEntry.of(Provider.OPENAI, "text-embedding-3-large")}. */
    static Embedder router(LlmRouter router, RouteEntry model) {
        Objects.requireNonNull(router, "router");
        return texts -> {
            EmbeddingResponse response = router.embed(EmbeddingRequest.builder()
                    .texts(texts)
                    .route(model == null ? null : List.of(model))
                    .build());
            return new Embeddings(response.getVectors(), response.getProviderUsed() + "/" + response.getModelUsed(),
                    response.getProviderUsed(), response.getUsage() == null ? 0 : response.getUsage().getInputTokens(),
                    response.getUsage() == null ? 0 : response.getUsage().getEstimatedCostUsdMicros());
        };
    }
}
