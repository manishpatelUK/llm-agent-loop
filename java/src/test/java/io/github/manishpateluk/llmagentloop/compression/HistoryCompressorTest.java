package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.provider.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryCompressorTest {

    private static final String MODEL = "test-compressor-model";

    @AfterEach
    void removeSyntheticModel() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, MODEL);
    }

    @Test
    void skipsCompressionWhenRequestAlreadyFits() {
        registerModel(100_000, 4_000);
        Request request = Request.builder().prompt("short prompt").build();

        CompressionOutcome outcome = HistoryCompressor.compress(request, Provider.ANTHROPIC, MODEL);

        assertThat(outcome.compressionApplied()).isFalse();
        assertThat(outcome.request()).isEqualTo(request);
    }

    @Test
    void compressesOversizedHistoryUsingLocalMethodsOnly() {
        registerModel(50, 5); // tiny window forces compression without needing an LlmRouter

        Request request = Request.builder()
                .prompt("what's next?")
                .history(List.of(
                        Message.user("turn one with a decent amount of padding text to burn tokens"),
                        Message.assistant("turn two with a decent amount of padding text to burn tokens"),
                        Message.user("turn three with a decent amount of padding text to burn tokens"),
                        Message.assistant("turn four, the most recent")))
                .build();

        CompressionOutcome outcome = HistoryCompressor.compress(
                request, Provider.ANTHROPIC, MODEL,
                List.of(CompressionMethod.STRUCTURAL_COMPACTION, CompressionMethod.SLIDING_WINDOW_TRUNCATION));

        assertThat(outcome.compressionApplied()).isTrue();
        assertThat(outcome.finalEstimatedTokens()).isLessThan(outcome.originalEstimatedTokens());
        assertThat(outcome.attempts()).isNotEmpty();
    }

    @Test
    void throwsCompressionExhaustedWhenNoMethodGetsUnderBudget() {
        registerModel(1, 0); // impossible budget

        Request request = Request.builder()
                .prompt("x")
                .history(List.of(Message.user("y")))
                .build();

        assertThatThrownBy(() -> HistoryCompressor.compress(
                request, Provider.ANTHROPIC, MODEL, List.of(CompressionMethod.STRUCTURAL_COMPACTION)))
                .isInstanceOf(CompressionExhaustedException.class)
                .satisfies(e -> {
                    CompressionExhaustedException exhausted = (CompressionExhaustedException) e;
                    assertThat(exhausted.getAttempts()).hasSize(1);
                    assertThat(exhausted.getTargetTokens()).isEqualTo(1);
                });
    }

    @Test
    void unknownModelThrowsIllegalArgumentException() {
        Request request = Request.builder().prompt("hi").build();

        assertThatThrownBy(() -> HistoryCompressor.compress(request, Provider.ANTHROPIC, "does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void registerModel(int contextWindowTokens, int maxOutputTokens) {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.ANTHROPIC)
                .model(MODEL)
                .contextWindowTokens(contextWindowTokens)
                .maxOutputTokens(maxOutputTokens)
                .build());
    }
}
