package io.github.manishpateluk.llmagentloop.conversation;

import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;

/**
 * Decides what a long chat session's older turns become — see {@link ConversationCompaction}.
 * {@link #SUMMARIZE} (the default) replaces them with one LLM-written summary; {@link #NONE} keeps
 * everything; or write your own — e.g. drop them, or summarize with your own service.
 */
@FunctionalInterface
public interface ConversationCompactor {

    /** Replaces older turns with a summary written by the loop's model (metered as {@code CONVERSATION_COMPACTION}). */
    ConversationCompactor SUMMARIZE = (older, summarizer, session) ->
            List.of(Message.system("Summary of the earlier conversation: " + summarizer.summarize(older)));

    /** Never compacts: the store keeps every turn. */
    ConversationCompactor NONE = (older, summarizer, session) -> older;

    /**
     * Returns what should replace {@code older} (the turns before the most recent ones being kept).
     * Return {@code older} itself to leave the conversation unchanged.
     *
     * @param summarizer summarizes messages with the loop's own model; its usage is metered
     * @param session    whose conversation this is
     */
    List<Message> compact(List<Message> older, Summarizer summarizer, Scope session);

    /** The loop's LLM summarization, offered to compactors. */
    @FunctionalInterface
    interface Summarizer {
        /** A concise summary preserving facts, decisions, open questions and commitments. */
        String summarize(List<Message> messages);
    }
}
