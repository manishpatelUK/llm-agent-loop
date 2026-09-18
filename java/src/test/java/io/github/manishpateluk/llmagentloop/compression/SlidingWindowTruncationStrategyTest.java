package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowTruncationStrategyTest {

    private final SlidingWindowTruncationStrategy strategy = new SlidingWindowTruncationStrategy();

    @Test
    void dropsOldestTurnsUntilUnderTarget() {
        Request request = Request.builder()
                .prompt("continue")
                .history(List.of(
                        Message.user("turn one, quite a lot of padding text to add tokens"),
                        Message.assistant("turn two, quite a lot of padding text to add tokens"),
                        Message.user("turn three, quite a lot of padding text to add tokens"),
                        Message.assistant("turn four, the most recent turn")))
                .build();

        int target = RequestTokenEstimator.estimateTokens(
                Request.builder().prompt("continue")
                        .history(List.of(Message.assistant("turn four, the most recent turn")))
                        .build()) + 1;

        Request compressed = strategy.compress(request, target, null);

        assertThat(compressed.getHistory()).hasSizeLessThan(request.getHistory().size());
        assertThat(compressed.getHistory().get(compressed.getHistory().size() - 1).getContent())
                .isEqualTo("turn four, the most recent turn");
    }

    @Test
    void neverDropsTheLastMessageEvenIfStillOverTarget() {
        Request request = Request.builder()
                .prompt("continue")
                .history(List.of(Message.user("the only turn, padded with extra words to be long")))
                .build();

        Request compressed = strategy.compress(request, 0, null);

        assertThat(compressed.getHistory()).hasSize(1);
    }

    @Test
    void preservesPinnedSystemTurns() {
        Request request = Request.builder()
                .prompt("continue")
                .history(List.of(
                        Message.system("pinned instructions"),
                        Message.user("old turn with plenty of padding text here"),
                        Message.assistant("newest turn")))
                .build();

        Request compressed = strategy.compress(request, 0, null);

        assertThat(compressed.getHistory()).contains(Message.system("pinned instructions"));
    }
}
