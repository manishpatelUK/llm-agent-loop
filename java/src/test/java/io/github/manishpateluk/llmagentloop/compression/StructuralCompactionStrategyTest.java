package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralCompactionStrategyTest {

    private final StructuralCompactionStrategy strategy = new StructuralCompactionStrategy();

    @Test
    void collapsesImmediateDuplicateMessages() {
        Request request = Request.builder()
                .prompt("continue")
                .history(List.of(
                        Message.tool("same result"),
                        Message.tool("same result"),
                        Message.assistant("ok")))
                .build();

        Request compressed = strategy.compress(request, 0, null);

        assertThat(compressed.getHistory()).containsExactly(Message.tool("same result"), Message.assistant("ok"));
    }

    @Test
    void normalizesRedundantWhitespace() {
        Request request = Request.builder()
                .prompt("continue")
                .history(List.of(Message.user("hello    world\n\n\n\nnext paragraph")))
                .build();

        Request compressed = strategy.compress(request, 0, null);

        assertThat(compressed.getHistory().get(0).getContent()).isEqualTo("hello world\n\nnext paragraph");
    }

    @Test
    void leavesDistinctMessagesUntouched() {
        Request request = Request.builder()
                .prompt("continue")
                .history(List.of(Message.user("first"), Message.assistant("second")))
                .build();

        Request compressed = strategy.compress(request, 0, null);

        assertThat(compressed.getHistory()).containsExactly(Message.user("first"), Message.assistant("second"));
    }
}
