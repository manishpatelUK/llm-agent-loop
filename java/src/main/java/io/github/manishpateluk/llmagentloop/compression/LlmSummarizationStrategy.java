package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * The last, always-available compression tier: asks an LLM (via {@code llm-router}'s own
 * fallback-list resolution) to condense the older part of the conversation into a single
 * briefing, keeping the most recent turns verbatim for immediate continuity.
 *
 * <p>Requires a non-null {@link LlmRouter}; throws if none was supplied so
 * {@link HistoryCompressor} records it as a failed attempt and moves on.
 */
final class LlmSummarizationStrategy implements CompressionStrategy {

    /** Most recent turns are kept verbatim so the model has unaltered immediate context. */
    private static final int VERBATIM_TAIL_SIZE = 2;

    @Override
    public Request compress(Request request, int targetTokens, LlmRouter router) {
        List<Message> history = request.getHistory();
        if (history.size() <= VERBATIM_TAIL_SIZE) {
            return request;
        }
        if (router == null) {
            throw new IllegalStateException("LLM_SUMMARIZATION requires an LlmRouter but none was supplied");
        }

        int splitAt = history.size() - VERBATIM_TAIL_SIZE;
        List<Message> toSummarize = history.subList(0, splitAt);
        List<Message> verbatimTail = history.subList(splitAt, history.size());

        String summary = summarize(router, toSummarize, targetTokens);

        List<Message> newHistory = new ArrayList<>(1 + verbatimTail.size());
        newHistory.add(Message.system("Conversation summary (compressed by llm-agent-loop): " + summary));
        newHistory.addAll(verbatimTail);

        return request.toBuilder().history(List.copyOf(newHistory)).build();
    }

    private static String summarize(LlmRouter router, List<Message> messages, int targetTokens) {
        String transcript = formatTranscript(messages);
        int targetWords = Math.max(50, targetTokens / 3);
        String prompt = "Summarize the following conversation transcript into a concise briefing "
                + "that preserves every decision, fact, open question, and action item a "
                + "continuing assistant would need. Target roughly " + targetWords + " words.\n\n"
                + "Transcript:\n" + transcript;

        Response response = router.complete(prompt);
        return response.getContent();
    }

    private static String formatTranscript(List<Message> messages) {
        StringBuilder transcript = new StringBuilder();
        for (Message message : messages) {
            transcript.append(roleLabel(message.getRole())).append(": ").append(message.getContent()).append("\n\n");
        }
        return transcript.toString();
    }

    private static String roleLabel(Role role) {
        return role.name();
    }
}
