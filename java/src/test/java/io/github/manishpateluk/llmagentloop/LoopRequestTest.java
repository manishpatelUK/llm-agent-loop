package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.model.Response;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoopRequestTest {

    private final Consumer<Response> onResult = response -> { };
    private final Consumer<Throwable> onError = error -> { };

    @Test
    void requiresPrompt() {
        assertThatThrownBy(() -> LoopRequest.builder().onResult(onResult).onError(onError).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void requiresOnResult() {
        assertThatThrownBy(() -> LoopRequest.builder().prompt("hi").onError(onError).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void requiresOnError() {
        assertThatThrownBy(() -> LoopRequest.builder().prompt("hi").onResult(onResult).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void filesDefaultToEmptyList() {
        LoopRequest request = LoopRequest.builder().prompt("hi").onResult(onResult).onError(onError).build();

        assertThat(request.files()).isEmpty();
    }

    @Test
    void agentProfileDefaultsToNullSinceItsOptional() {
        LoopRequest request = LoopRequest.builder().prompt("hi").onResult(onResult).onError(onError).build();

        assertThat(request.agentProfile()).isNull();
    }

    @Test
    void onMessageDefaultsToANoOpRatherThanNull() {
        LoopRequest request = LoopRequest.builder().prompt("hi").onResult(onResult).onError(onError).build();

        assertThat(request.onMessage()).isNotNull();
        request.onMessage().accept(AgentMessage.of(MessageType.INFO, "should not throw"));
    }

    @Test
    void builderCarriesThroughSuppliedOptionalValues() {
        File file = new File("notes.txt");
        Consumer<AgentMessage> onMessage = message -> { };

        LoopRequest request = LoopRequest.builder()
                .prompt("hi")
                .files(List.of(file))
                .onResult(onResult)
                .onError(onError)
                .onMessage(onMessage)
                .build();

        assertThat(request.files()).containsExactly(file);
        assertThat(request.onMessage()).isSameAs(onMessage);
    }
}
