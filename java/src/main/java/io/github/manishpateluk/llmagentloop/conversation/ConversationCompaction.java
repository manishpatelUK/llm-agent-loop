package io.github.manishpateluk.llmagentloop.conversation;

import java.util.Objects;

/**
 * When and how a chat session's stored history is compacted, so it can't grow without bound. After
 * a turn is saved, if the session holds more than {@code maxMessages} messages, everything but the
 * most recent {@code keepRecent} is handed to the {@code compactor}, and the store's conversation
 * is replaced with its result followed by the recent messages. Set with
 * {@code AgentLoop.builder().conversationCompaction(...)}.
 *
 * @param maxMessages compact once a session holds more messages than this (a turn is two messages)
 * @param keepRecent  how many of the most recent messages are always kept verbatim
 * @param compactor   what older messages become; {@link ConversationCompactor#SUMMARIZE} by default
 */
public record ConversationCompaction(int maxMessages, int keepRecent, ConversationCompactor compactor) {

    /** Compact past 40 messages (20 turns), keeping the last 10 verbatim and summarizing the rest. */
    public static final ConversationCompaction DEFAULT = new ConversationCompaction(40, 10, ConversationCompactor.SUMMARIZE);

    /** Never compact. */
    public static final ConversationCompaction OFF = new ConversationCompaction(Integer.MAX_VALUE, 0, ConversationCompactor.NONE);

    public ConversationCompaction {
        Objects.requireNonNull(compactor, "compactor");
        if (keepRecent < 0 || maxMessages <= keepRecent) {
            throw new IllegalArgumentException("Need 0 <= keepRecent < maxMessages, got keepRecent=" + keepRecent
                    + ", maxMessages=" + maxMessages);
        }
    }

    /** Summarizing compaction with custom thresholds. */
    public static ConversationCompaction summarizing(int maxMessages, int keepRecent) {
        return new ConversationCompaction(maxMessages, keepRecent, ConversationCompactor.SUMMARIZE);
    }
}
