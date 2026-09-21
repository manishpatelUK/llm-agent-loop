package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Free, lossless-ish cleanup applied before any summarization is attempted: collapses immediate
 * repeats of the same message (e.g. a tool re-emitting an identical result) and normalizes
 * redundant whitespace. Never removes information a later turn couldn't otherwise be missing.
 */
final class StructuralCompactionStrategy implements CompressionStrategy {

    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern RUNS_OF_SPACES = Pattern.compile("[ \\t]{2,}");

    @Override
    public Request compress(Request request, int targetTokens, LlmRouter router) {
        List<Message> original = request.getHistory();
        List<Message> compacted = new ArrayList<>(original.size());

        Message previous = null;
        for (Message message : original) {
            Message normalized = withContent(message, normalizeWhitespace(message.getContent()));
            boolean duplicateOfPrevious = previous != null
                    && previous.getRole() == normalized.getRole()
                    && previous.getContent().equals(normalized.getContent());
            if (!duplicateOfPrevious) {
                compacted.add(normalized);
            }
            previous = normalized;
        }

        return request.toBuilder().history(List.copyOf(compacted)).build();
    }

    private static String normalizeWhitespace(String content) {
        if (content == null) {
            return null;
        }
        String collapsedBlankLines = BLANK_LINES.matcher(content).replaceAll("\n\n");
        return RUNS_OF_SPACES.matcher(collapsedBlankLines).replaceAll(" ").strip();
    }

    /**
     * Preserves {@code toolCalls}/{@code toolCallId} across the rewrite — losing either would
     * silently regress a native tool-call/tool-result turn back to flattened text once it's
     * compressed (see {@code Message}'s class-level doc in llm-router).
     */
    private static Message withContent(Message message, String content) {
        return Message.builder()
                .role(message.getRole())
                .content(content)
                .toolCalls(message.getToolCalls())
                .toolCallId(message.getToolCallId())
                .build();
    }
}
