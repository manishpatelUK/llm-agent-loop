package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.model.Request;

import java.util.List;

/**
 * Result of a {@link HistoryCompressor#compress} call.
 *
 * @param request               the request to actually send — unchanged from the input when
 *                              {@code compressionApplied} is {@code false}
 * @param compressionApplied    whether any compression method reduced the request
 * @param originalEstimatedTokens estimated tokens (including the safety margin) before compression
 * @param finalEstimatedTokens  estimated tokens (including the safety margin) after compression
 * @param targetTokens          the token budget compression was aiming to get under
 * @param attempts              every {@link CompressionMethod} tried, in order, with its result
 */
public record CompressionOutcome(
        Request request,
        boolean compressionApplied,
        int originalEstimatedTokens,
        int finalEstimatedTokens,
        int targetTokens,
        List<CompressionAttempt> attempts) {

    static CompressionOutcome notNeeded(Request request, int estimatedTokens, int targetTokens) {
        return new CompressionOutcome(request, false, estimatedTokens, estimatedTokens, targetTokens, List.of());
    }

    static CompressionOutcome compressed(
            Request request, int originalTokens, int finalTokens, int targetTokens, List<CompressionAttempt> attempts) {
        return new CompressionOutcome(request, true, originalTokens, finalTokens, targetTokens, List.copyOf(attempts));
    }
}
