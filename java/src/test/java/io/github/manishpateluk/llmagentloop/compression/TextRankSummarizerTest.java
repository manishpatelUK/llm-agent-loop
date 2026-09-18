package io.github.manishpateluk.llmagentloop.compression;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextRankSummarizerTest {

    private static final String TRANSCRIPT =
            "The customer asked about refund policy for digital goods. "
            + "Our policy allows refunds within 14 days of purchase for unused licenses. "
            + "The customer said they purchased the license three days ago and have not activated it. "
            + "Support confirmed the refund is eligible under the current policy. "
            + "Support then asked whether the customer wanted a full refund or store credit. "
            + "The customer chose a full refund to their original payment method. "
            + "Support processed the refund request and provided a confirmation number.";

    @Test
    void keepsFewerSentencesThanOriginal() {
        String summary = TextRankSummarizer.summarize(TRANSCRIPT, 0.5);

        int originalSentences = TextRankSummarizer.splitSentences(TRANSCRIPT).size();
        int summarySentences = TextRankSummarizer.splitSentences(summary).size();

        assertThat(summarySentences).isLessThan(originalSentences);
        assertThat(summarySentences).isGreaterThanOrEqualTo(1);
    }

    @Test
    void preservesOriginalSentenceOrder() {
        String summary = TextRankSummarizer.summarize(TRANSCRIPT, 0.5);
        List<String> original = TextRankSummarizer.splitSentences(TRANSCRIPT);
        List<String> kept = TextRankSummarizer.splitSentences(summary);

        int searchFrom = 0;
        for (String sentence : kept) {
            int index = original.indexOf(sentence);
            assertThat(index).isGreaterThanOrEqualTo(searchFrom);
            searchFrom = index + 1;
        }
    }

    @Test
    void returnsTextUnchangedWhenAlreadyShorterThanRequestedFraction() {
        String shortText = "One sentence only.";
        assertThat(TextRankSummarizer.summarize(shortText, 0.5)).isEqualTo(shortText);
    }

    @Test
    void handlesBlankInputGracefully() {
        assertThat(TextRankSummarizer.summarize("", 0.5)).isEmpty();
        assertThat(TextRankSummarizer.summarize(null, 0.5)).isNull();
    }
}
