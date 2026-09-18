package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmSummarizationStrategyTest {

    private final LlmSummarizationStrategy strategy = new LlmSummarizationStrategy();

    @Test
    void throwsWhenNoRouterSupplied() {
        Request request = Request.builder()
                .prompt("continue")
                .history(List.of(Message.user("a"), Message.assistant("b"), Message.user("c")))
                .build();

        assertThatThrownBy(() -> strategy.compress(request, 10, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doesNothingWhenHistoryIsAlreadyAtOrBelowTheVerbatimTailSize() {
        Request request = Request.builder()
                .prompt("continue")
                .history(List.of(Message.user("a"), Message.assistant("b")))
                .build();

        // No router needed: with <= 2 turns of history there's nothing to summarize, so the
        // strategy returns before ever consulting the router.
        Request result = strategy.compress(request, 10, null);

        assertThat(result).isEqualTo(request);
    }
}
