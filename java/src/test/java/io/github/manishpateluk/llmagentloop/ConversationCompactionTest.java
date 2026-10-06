package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmagentloop.conversation.ConversationCompaction;
import io.github.manishpateluk.llmagentloop.conversation.ConversationCompactor;
import io.github.manishpateluk.llmagentloop.conversation.ConversationStore;
import io.github.manishpateluk.llmagentloop.conversation.InMemoryConversationStore;
import io.github.manishpateluk.llmagentloop.usage.UsagePurpose;
import io.github.manishpateluk.llmagentloop.usage.UsageRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationCompactionTest {

    private static final Scope SESSION = Scope.of("acme", "alice", "s1");

    private final ScriptedModel model = new ScriptedModel();
    private final InMemoryConversationStore store = new InMemoryConversationStore();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void byDefaultOlderTurnsAreSummarizedOnceTheSessionGrowsPastTheThreshold() throws InterruptedException {
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        AgentLoop loop = model.loop().conversations(store).usageMeter(records::add)
                .conversationCompaction(ConversationCompaction.summarizing(6, 2)).build();
        model.respond(request -> request.getPrompt().startsWith("Summarize this conversation")
                ? text("User is planning a launch in March.")
                : text("answer to " + request.getPrompt()));

        for (int i = 1; i <= 3; i++) {
            run(loop, "turn " + i);
        }
        assertThat(store.load(SESSION)).hasSize(6); // at the threshold: nothing compacted yet

        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop, request("turn 4"));

        List<Message> stored = store.load(SESSION);
        assertThat(stored).hasSize(3);
        assertThat(stored.getFirst().getRole()).isEqualTo(Role.SYSTEM);
        assertThat(stored.getFirst().getContent()).isEqualTo("Summary of the earlier conversation: User is planning a launch in March.");
        assertThat(stored.subList(1, 3)).extracting(Message::getContent).containsExactly("turn 4", "answer to turn 4");
        assertThat(records).extracting(UsageRecord::purpose).contains(UsagePurpose.CONVERSATION_COMPACTION);
        assertThat(capture.result().usage().calls()).isEqualTo(2); // the step plus the summary
        assertThat(capture.messages()).anyMatch(m -> m.message().startsWith("Compacted the conversation"));

        // The next turn sees the summary in its context.
        model.requests.clear();
        run(loop, "turn 5");
        assertThat(model.requests.getFirst().getHistory()).anyMatch(m -> m.getContent().contains("launch in March"));
    }

    @Test
    void aCustomCompactorCanReplaceTheDefaultSummary() throws InterruptedException {
        ConversationCompactor dropOlder = (older, summarizer, session) -> List.of();
        AgentLoop loop = model.loop().conversations(store)
                .conversationCompaction(new ConversationCompaction(4, 2, dropOlder)).build();
        model.respond(request -> text("ok"));

        for (int i = 1; i <= 3; i++) {
            run(loop, "turn " + i);
        }

        assertThat(store.load(SESSION)).extracting(Message::getContent).containsExactly("turn 3", "ok");
        assertThat(model.requests).noneMatch(r -> r.getPrompt().startsWith("Summarize"));
    }

    @Test
    void compactionCanBeSwitchedOff() throws InterruptedException {
        AgentLoop loop = model.loop().conversations(store).conversationCompaction(ConversationCompaction.OFF).build();
        model.respond(request -> text("ok"));

        for (int i = 1; i <= 25; i++) {
            run(loop, "turn " + i);
        }

        assertThat(store.load(SESSION)).hasSize(50);
    }

    @Test
    void aCallbackBackedStoreIsCompactedThroughItsReplaceCallback() throws InterruptedException {
        Map<String, List<Message>> table = new ConcurrentHashMap<>();
        ConversationStore database = ConversationStore.of(
                session -> table.getOrDefault(session.key(), List.of()),
                (session, messages) -> table.computeIfAbsent(session.key(), k -> new CopyOnWriteArrayList<>()).addAll(messages),
                (session, messages) -> table.put(session.key(), new CopyOnWriteArrayList<>(messages)));
        AgentLoop loop = model.loop().conversations(database)
                .conversationCompaction(ConversationCompaction.summarizing(4, 2)).build();
        model.respond(request -> request.getPrompt().startsWith("Summarize") ? text("short summary") : text("ok"));

        for (int i = 1; i <= 3; i++) {
            run(loop, "turn " + i);
        }

        assertThat(table.get(SESSION.key())).hasSize(3);
        assertThat(table.get(SESSION.key()).getFirst().getContent()).endsWith("short summary");
    }

    @Test
    void aStoreWithoutReplaceIsLeftAloneWithAWarning() throws InterruptedException {
        List<Message> rows = new ArrayList<>();
        ConversationStore appendOnly = ConversationStore.of(session -> List.copyOf(rows), (session, messages) -> rows.addAll(messages), null);
        AgentLoop loop = model.loop().conversations(appendOnly)
                .conversationCompaction(ConversationCompaction.summarizing(2, 0)).build();
        model.respond(request -> text("ok"));

        run(loop, "turn 1");
        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop, request("turn 2"));

        assertThat(rows).hasSize(4);
        assertThat(capture.messages()).anyMatch(m -> m.type() == MessageType.WARNING && m.message().contains("replace()"));
        assertThat(capture.error()).isNull();
    }

    @Test
    void thresholdsAreValidated() {
        assertThatThrownBy(() -> ConversationCompaction.summarizing(10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationCompaction.summarizing(10, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private void run(AgentLoop loop, String prompt) throws InterruptedException {
        assertThat(AgentLoopRunSupport.run(loop, request(prompt)).error()).isNull();
    }

    private static LoopRequest.LoopRequestBuilder request(String prompt) {
        return LoopRequest.builder().prompt(prompt).scope(SESSION).agentProfile(RECURSIVE);
    }
}
