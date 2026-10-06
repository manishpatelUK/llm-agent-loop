package io.github.manishpateluk.llmagentloop.conversation;

import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The turns of each chat session, so a run can see what was said before — "make it shorter" needs
 * to know what "it" is. Keyed by the full {@link Scope} (tenant, user <em>and</em> session): each
 * session has its own conversation; deciding what a session is stays with the implementor.
 *
 * <p>For each completed run the loop appends two messages: the user's prompt and the final answer.
 * Tool calls and intermediate steps aren't stored (they're in the run's {@code Execution} trace),
 * which keeps later context compact. When a session grows long, {@link ConversationCompaction}
 * replaces its older turns — by default with a summary — via {@link #replace}.
 *
 * <p>Two ways to back it with your own storage: implement this interface, or pass three
 * callbacks to {@link #of}. {@link InMemoryConversationStore} is for development. Calls come from
 * runs' virtual threads, so blocking I/O is fine; implementations must be safe for concurrent runs.
 */
public interface ConversationStore {

    /** Remembers nothing: every run starts a fresh conversation. */
    ConversationStore NONE = new ConversationStore() {
        @Override
        public List<Message> load(Scope session) {
            return List.of();
        }

        @Override
        public void append(Scope session, List<Message> messages) {
        }

        @Override
        public void replace(Scope session, List<Message> messages) {
        }
    };

    /** The session's earlier messages, oldest first. */
    List<Message> load(Scope session);

    /** Adds {@code messages} to the end of the session's conversation. */
    void append(Scope session, List<Message> messages);

    /**
     * Replaces the session's whole conversation with {@code messages} — used by compaction. Stores
     * that don't support it (the default throws {@link UnsupportedOperationException}) are simply
     * never compacted; a warning says so.
     */
    default void replace(Scope session, List<Message> messages) {
        throw new UnsupportedOperationException("replace");
    }

    /**
     * A store whose storage is three callbacks you supply — typically your own database code:
     *
     * <pre>{@code
     * ConversationStore store = ConversationStore.of(
     *         session -> chatDao.messages(session.key()),
     *         (session, messages) -> chatDao.append(session.key(), messages),
     *         (session, messages) -> chatDao.replaceAll(session.key(), messages));
     * }</pre>
     *
     * {@code replace} may be {@code null}, in which case the store is never compacted.
     */
    static ConversationStore of(Function<Scope, List<Message>> load,
                                BiConsumer<Scope, List<Message>> append,
                                BiConsumer<Scope, List<Message>> replace) {
        Objects.requireNonNull(load, "load");
        Objects.requireNonNull(append, "append");
        return new ConversationStore() {
            @Override
            public List<Message> load(Scope session) {
                List<Message> messages = load.apply(session);
                return messages == null ? List.of() : messages;
            }

            @Override
            public void append(Scope session, List<Message> messages) {
                append.accept(session, messages);
            }

            @Override
            public void replace(Scope session, List<Message> messages) {
                if (replace == null) {
                    throw new UnsupportedOperationException("replace");
                }
                replace.accept(session, messages);
            }
        };
    }
}
