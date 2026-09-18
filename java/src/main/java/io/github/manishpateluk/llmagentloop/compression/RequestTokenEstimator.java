package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.model.Attachment;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.routing.TokenEstimator;

/**
 * Estimates the token footprint of a whole {@link Request} — system instructions, history,
 * prompt, and attachments — by aggregating {@code llm-router}'s {@link TokenEstimator}, which
 * only estimates a single raw string.
 */
final class RequestTokenEstimator {

    /**
     * Attachments are sent to providers base64-encoded, which inflates raw bytes by ~4/3.
     * Applying {@link TokenEstimator}'s 4-chars-per-token heuristic to that inflated length
     * simplifies to one token per ~3 raw bytes.
     */
    private static final double BYTES_PER_TOKEN = 3.0;

    private RequestTokenEstimator() {
    }

    static int estimateTokens(Request request) {
        int total = 0;

        if (request.getSystemInstructions() != null) {
            total += TokenEstimator.estimateTokens(request.getSystemInstructions());
        }
        for (Message message : request.getHistory()) {
            total += TokenEstimator.estimateTokens(message.getContent());
        }
        if (request.getPrompt() != null) {
            total += TokenEstimator.estimateTokens(request.getPrompt());
        }
        for (Attachment attachment : request.getAttachments()) {
            total += estimateAttachmentTokens(attachment);
        }
        return total;
    }

    /** Same as {@link #estimateTokens(Request)}, inflated by {@code marginFraction} for safety. */
    static int estimateTokensWithMargin(Request request, double marginFraction) {
        return (int) Math.ceil(estimateTokens(request) * (1.0 + marginFraction));
    }

    private static int estimateAttachmentTokens(Attachment attachment) {
        byte[] data = attachment.getData();
        if (data == null || data.length == 0) {
            return 0;
        }
        return (int) Math.ceil(data.length / BYTES_PER_TOKEN);
    }
}
