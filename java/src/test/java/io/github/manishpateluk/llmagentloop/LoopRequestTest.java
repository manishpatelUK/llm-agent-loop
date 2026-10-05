package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmagentloop.tool.UnregisteredToolHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoopRequestTest {

    private final Consumer<AgentLoopResult> onResult = result -> { };
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
    void attachmentsDefaultToEmptyAndAreSavedToTheWorkspaceUnlessTurnedOff() {
        LoopRequest request = LoopRequest.builder().prompt("hi").onResult(onResult).onError(onError).build();

        assertThat(request.attachments()).isEmpty();
        assertThat(request.saveAttachments()).isTrue();
        assertThat(request.history()).isNull();
        assertThat(request.routerConfig()).isNull();
    }

    @Test
    void inputFilesGuessTheirMediaTypeAndCopyTheirBytes() {
        byte[] bytes = {1, 2, 3};
        InputFile file = InputFile.of("photo.PNG", bytes);
        bytes[0] = 9;

        assertThat(file.mediaType()).isEqualTo("image/png");
        assertThat(file.data()).containsExactly(1, 2, 3);
        assertThat(InputFile.of("data.csv", new java.io.ByteArrayInputStream("a,b".getBytes())).mediaType()).isEqualTo("text/csv");
        assertThat(InputFile.of("mystery.bin", bytes).mediaType()).isEqualTo("application/octet-stream");
        assertThatThrownBy(() -> InputFile.of(" ", bytes)).isInstanceOf(IllegalArgumentException.class);
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
        request.onMessage().accept(AgentMessage.of(UUID.randomUUID(), 0, MessageType.INFO, "should not throw"));
    }

    @Test
    void onUnregisteredToolDefaultsToDecliningEveryCall() {
        LoopRequest request = LoopRequest.builder().prompt("hi").onResult(onResult).onError(onError).build();

        assertThat(request.onUnregisteredTool()).isSameAs(UnregisteredToolHandler.NONE);
    }

    @Test
    void builderCarriesThroughSuppliedOptionalValues() {
        InputFile file = InputFile.of("notes.txt", "hello".getBytes());
        Consumer<AgentMessage> onMessage = message -> { };

        LoopRequest request = LoopRequest.builder()
                .prompt("hi")
                .attachments(List.of(file))
                .saveAttachments(false)
                .onResult(onResult)
                .onError(onError)
                .onMessage(onMessage)
                .build();

        assertThat(request.attachments()).containsExactly(file);
        assertThat(request.saveAttachments()).isFalse();
        assertThat(request.onMessage()).isSameAs(onMessage);
    }

    @Test
    void costAndTimeBoundsDefaultToNullMeaningUnbounded() {
        LoopRequest request = LoopRequest.builder().prompt("hi").onResult(onResult).onError(onError).build();

        assertThat(request.maxCostUsdCents()).isNull();
        assertThat(request.maxDuration()).isNull();
    }

    @Test
    void costAndTimeBoundsCanBeSupplied() {
        LoopRequest request = LoopRequest.builder()
                .prompt("hi")
                .onResult(onResult)
                .onError(onError)
                .maxCostUsdCents(100)
                .maxDuration(Duration.ofMinutes(5))
                .build();

        assertThat(request.maxCostUsdCents()).isEqualTo(100);
        assertThat(request.maxDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void maxCostUsdCentsMustBePositiveWhenSupplied() {
        assertThatThrownBy(() -> LoopRequest.builder()
                .prompt("hi").onResult(onResult).onError(onError).maxCostUsdCents(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoopRequest.builder()
                .prompt("hi").onResult(onResult).onError(onError).maxCostUsdCents(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxDurationMustBePositiveWhenSupplied() {
        assertThatThrownBy(() -> LoopRequest.builder()
                .prompt("hi").onResult(onResult).onError(onError).maxDuration(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoopRequest.builder()
                .prompt("hi").onResult(onResult).onError(onError).maxDuration(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
