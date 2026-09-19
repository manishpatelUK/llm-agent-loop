package io.github.manishpateluk.llmagentloop.compression;

import lombok.Getter;

import java.util.List;

/**
 * Thrown by {@link HistoryCompressor} when every {@link CompressionMethod} in the preference
 * list has been tried and the request still exceeds the target token budget.
 */
@Getter
public class CompressionExhaustedException extends RuntimeException {

    private final int targetTokens;
    private final int finalEstimatedTokens;
    private final List<CompressionAttempt> attempts;

    CompressionExhaustedException(int targetTokens, int finalEstimatedTokens, List<CompressionAttempt> attempts) {
        super(buildMessage(targetTokens, finalEstimatedTokens, attempts));
        this.targetTokens = targetTokens;
        this.finalEstimatedTokens = finalEstimatedTokens;
        this.attempts = List.copyOf(attempts);
    }

    private static String buildMessage(int targetTokens, int finalEstimatedTokens, List<CompressionAttempt> attempts) {
        StringBuilder message = new StringBuilder()
                .append("Compression exhausted: still ~")
                .append(finalEstimatedTokens)
                .append(" estimated tokens after trying all ")
                .append(attempts.size())
                .append(" method(s), target was ")
                .append(targetTokens)
                .append(" tokens.");
        for (CompressionAttempt attempt : attempts) {
            message.append("\n  - ")
                    .append(attempt.method())
                    .append(": ")
                    .append(attempt.succeeded() ? "reduced " + attempt.tokensBefore() + " -> " + attempt.tokensAfter() : "no effect")
                    .append(attempt.note() != null ? " (" + attempt.note() + ")" : "");
        }
        return message.toString();
    }
}
