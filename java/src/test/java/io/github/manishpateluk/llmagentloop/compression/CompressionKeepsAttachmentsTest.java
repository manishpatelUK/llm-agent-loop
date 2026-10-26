package io.github.manishpateluk.llmagentloop.compression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.github.manishpateluk.llmrouter.model.Attachment;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;

import org.junit.jupiter.api.Test;

/** Compression rewrites messages' text; files a message showed the model stay on it, and count toward its size. */
class CompressionKeepsAttachmentsTest {

    private static final Attachment PNG = Attachment.builder().mediaType("image/png")
            .data(new byte[] {(byte) 0x89, 'P', 'N', 'G'}).filename("chart.png").build();

    private static Request withShownFile(String text) {
        return Request.builder().prompt("continue")
                .history(List.of(Message.user(text, List.of(PNG)), Message.assistant("ok")))
                .build();
    }

    @Test
    void structuralCompactionKeepsAMessagesAttachments() {
        Request compressed = new StructuralCompactionStrategy().compress(withShownFile("look    at   this\n\n\n\nchart"), 0, null);

        assertThat(compressed.getHistory().getFirst().getContent()).isEqualTo("look at this\n\nchart");
        assertThat(compressed.getHistory().getFirst().getAttachments()).containsExactly(PNG);
    }

    @Test
    void extractiveSummarizationKeepsAMessagesAttachments() {
        String longText = "The chart shows revenue. ".repeat(4) + "Costs fell sharply in March. " + "Margins rose. ".repeat(4)
                + "The outlook is strong. Hiring will continue. Cash is ample. Risks are modest.";
        Request compressed = new ExtractiveSummarizationStrategy().compress(withShownFile(longText), 0, null);

        assertThat(compressed.getHistory().getFirst().getAttachments()).containsExactly(PNG);
    }

    @Test
    void historyAttachmentsCountTowardTheEstimate() {
        Request withFile = withShownFile("look");
        Request without = Request.builder().prompt("continue")
                .history(List.of(Message.user("look"), Message.assistant("ok"))).build();

        assertThat(RequestTokenEstimator.estimateTokens(withFile)).isGreaterThan(RequestTokenEstimator.estimateTokens(without));
    }
}
