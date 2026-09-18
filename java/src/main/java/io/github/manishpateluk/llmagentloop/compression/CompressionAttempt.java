package io.github.manishpateluk.llmagentloop.compression;

/**
 * The outcome of trying a single {@link CompressionMethod} while compressing a request.
 *
 * @param method       the method that was tried
 * @param tokensBefore estimated token count immediately before this attempt
 * @param tokensAfter  estimated token count immediately after this attempt (equal to
 *                     {@code tokensBefore} when the attempt made no progress or failed)
 * @param succeeded    whether this attempt reduced the token count
 * @param note         a short explanation when {@code succeeded} is {@code false} (e.g. an
 *                     exception message), otherwise {@code null}
 */
public record CompressionAttempt(
        CompressionMethod method,
        int tokensBefore,
        int tokensAfter,
        boolean succeeded,
        String note) {

    static CompressionAttempt success(CompressionMethod method, int tokensBefore, int tokensAfter) {
        return new CompressionAttempt(method, tokensBefore, tokensAfter, true, null);
    }

    static CompressionAttempt failure(CompressionMethod method, int tokensAtAttempt, String note) {
        return new CompressionAttempt(method, tokensAtAttempt, tokensAtAttempt, false, note);
    }
}
