package io.github.manishpateluk.llmagentloop.conversation;

import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link ConversationStore} held in this process's heap: lost on restart and unbounded, so for
 * development and tests rather than production.
 */
public final class InMemoryConversationStore implements ConversationStore {

    private final Map<Scope, List<Message>> conversations = new ConcurrentHashMap<>();

    @Override
    public List<Message> load(Scope session) {
        return List.copyOf(conversations.getOrDefault(session, List.of()));
    }

    @Override
    public void append(Scope session, List<Message> messages) {
        conversations.computeIfAbsent(session, key -> new CopyOnWriteArrayList<>()).addAll(messages);
    }

    /** Forgets a session's conversation, e.g. when the user starts over. */
    public void clear(Scope session) {
        conversations.remove(session);
    }
}
