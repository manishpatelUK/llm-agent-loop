package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.model.Request;

/**
 * A single compression technique. Implementations make a best-effort attempt to reduce the
 * token footprint of {@code request} and return a new {@link Request}; they do not need to hit
 * {@code targetTokens} exactly, since {@link HistoryCompressor} re-measures after every attempt
 * and moves on to the next method in the preference list if more reduction is still needed.
 *
 * <p>Implementations that cannot run at all (e.g. an LLM-backed strategy with no
 * {@link LlmRouter} supplied) should throw a {@link RuntimeException}; {@link HistoryCompressor}
 * treats that the same as "no reduction achieved" and moves on to the next method.
 */
@FunctionalInterface
public interface CompressionStrategy {

    /**
     * @param request     the request to compress
     * @param targetTokens the token budget the caller is trying to get under (a hint, not a hard
     *                     contract the strategy must satisfy in one call)
     * @param router      an {@link LlmRouter} to use for LLM-backed strategies, or {@code null}
     *                    if none was supplied
     * @return a new, possibly-smaller {@link Request}
     */
    Request compress(Request request, int targetTokens, LlmRouter router);
}
