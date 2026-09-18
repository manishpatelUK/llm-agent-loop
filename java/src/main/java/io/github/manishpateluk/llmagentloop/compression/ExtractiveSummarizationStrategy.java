package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies {@link TextRankSummarizer} to each history message that's long enough to be worth
 * summarizing, keeping the highest-ranked half of its sentences. Short messages (a handful of
 * sentences or fewer) are left verbatim — extraction isn't worthwhile on them and they're often
 * user intent that shouldn't be lossy-compressed.
 */
final class ExtractiveSummarizationStrategy implements CompressionStrategy {

    private static final int MIN_SENTENCES_TO_SUMMARIZE = 4;
    private static final double KEEP_FRACTION = 0.5;

    @Override
    public Request compress(Request request, int targetTokens, LlmRouter router) {
        List<Message> summarized = new ArrayList<>(request.getHistory().size());
        for (Message message : request.getHistory()) {
            summarized.add(summarizeIfWorthwhile(message));
        }
        return request.toBuilder().history(List.copyOf(summarized)).build();
    }

    private static Message summarizeIfWorthwhile(Message message) {
        if (message.getRole() == Role.SYSTEM || message.getContent() == null) {
            return message;
        }
        if (TextRankSummarizer.splitSentences(message.getContent()).size() < MIN_SENTENCES_TO_SUMMARIZE) {
            return message;
        }
        String condensed = TextRankSummarizer.summarize(message.getContent(), KEEP_FRACTION);
        return switch (message.getRole()) {
            case USER -> Message.user(condensed);
            case ASSISTANT -> Message.assistant(condensed);
            case TOOL -> Message.tool(condensed);
            case SYSTEM -> message;
        };
    }
}
