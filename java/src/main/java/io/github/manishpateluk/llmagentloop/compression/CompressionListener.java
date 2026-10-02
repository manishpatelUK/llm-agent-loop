package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.provider.Provider;

/**
 * Notified whenever {@link HistoryCompressor#compress} actually does something — compresses a
 * request, or gives up on one — while bound via {@link HistoryCompressor#withListener}. Calls
 * that needed no compression aren't reported.
 *
 * <p>This is how a compression-enabled router's events reach whoever made the call (e.g. an
 * {@code AgentLoop} run's {@code onMessage}/{@code Execution} trace), even though the
 * {@code RequestInterceptor} doing the compressing has no reference back to its caller.
 */
@FunctionalInterface
public interface CompressionListener {

    /** {@code outcome.compressionApplied()} is always {@code true} here. */
    void compressed(Provider provider, String model, CompressionOutcome outcome);

    /** Every method was tried and the request still doesn't fit {@code model}; the exception is about to be thrown. */
    default void exhausted(Provider provider, String model, CompressionExhaustedException failure) {
    }
}
