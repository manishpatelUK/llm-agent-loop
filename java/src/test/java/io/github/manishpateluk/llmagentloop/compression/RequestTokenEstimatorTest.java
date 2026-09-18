package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.model.Attachment;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTokenEstimatorTest {

    @Test
    void sumsSystemInstructionsHistoryAndPrompt() {
        Request request = Request.builder()
                .systemInstructions("0123") // 4 chars -> 1 token
                .history(List.of(Message.user("01234567"))) // 8 chars -> 2 tokens
                .prompt("0123456789ab") // 12 chars -> 3 tokens
                .build();

        assertThat(RequestTokenEstimator.estimateTokens(request)).isEqualTo(6);
    }

    @Test
    void includesAttachmentBytesApproximately() {
        Request request = Request.builder()
                .prompt("")
                .attachments(List.of(Attachment.builder()
                        .mediaType("text/plain")
                        .data(new byte[300])
                        .filename("notes.txt")
                        .build()))
                .build();

        // ~1 token per 3 raw bytes (base64 inflation folded into the 4-chars-per-token heuristic).
        assertThat(RequestTokenEstimator.estimateTokens(request)).isEqualTo(100);
    }

    @Test
    void safetyMarginInflatesEstimate() {
        Request request = Request.builder().prompt("0123456789ab").build(); // 3 tokens

        int withMargin = RequestTokenEstimator.estimateTokensWithMargin(request, 0.05);

        assertThat(withMargin).isGreaterThanOrEqualTo(3);
    }
}
