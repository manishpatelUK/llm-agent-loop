package io.github.manishpateluk.llmagentloop.conversation;

import com.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;

/**
 * The turns of each chat session, so a run can see what was said before — "make it shorter" needs
 * to know what "it" is. Keyed by the full {@link Scope} (tenant, user <em>and</em> session): each
 * session has its own conversation; deciding what a session is stays with the implementor.
 *
 * <p>For each completed run the loop appends two messages: the user's prompt and the final answer.
 * Tool calls and intermediate steps aren't stored (they're in the run's {@code Execution} trace),
 * which keeps later context compact. Implement this over your own database to persist chats;
 * {@link InMemoryConversationStore} is for development. Calls come from runs' virtual threads, so
 * blocking I/O is fine; implementations must be safe for concurrent runs.
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
    };

    /** The session's earlier messages, oldest first. */
    List<Message> load(Scope session);

    /** Adds {@code messages} to the end of the session's conversation. */
    void append(Scope session, List<Message> messages);
}
