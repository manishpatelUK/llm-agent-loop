package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmagentloop.conversation.InMemoryConversationStore;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.complete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTest {

    private static final Scope MONDAY = Scope.of("acme", "alice", "monday");

    private final ScriptedModel model = new ScriptedModel();
    private final InMemoryConversationStore store = new InMemoryConversationStore();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void eachTurnSeesTheSessionsEarlierTurnsAndIsSavedAfterwards() {
        AgentLoop loop = model.loop().conversations(store).build();

        model.respond(request -> complete("Here is the draft email."));
        loop.runAndWait(request("Draft an email to the team", MONDAY));
        model.requests.clear();

        model.respond(request -> complete("Shorter version."));
        loop.runAndWait(request("Make it shorter", MONDAY));

        List<Message> seen = model.requests.getFirst().getHistory();
        assertThat(seen).extracting(Message::getRole, Message::getContent).startsWith(
                org.assertj.core.groups.Tuple.tuple(Role.USER, "Draft an email to the team"),
                org.assertj.core.groups.Tuple.tuple(Role.ASSISTANT, "Here is the draft email."));
        assertThat(model.requests.getFirst().getPrompt()).isEqualTo("Make it shorter");
        assertThat(store.load(MONDAY)).extracting(Message::getContent).containsExactly(
                "Draft an email to the team", "Here is the draft email.", "Make it shorter", "Shorter version.");
    }

    @Test
    void sessionsAreSeparateEvenForTheSameUser() {
        AgentLoop loop = model.loop().conversations(store).build();
        loop.runAndWait(request("first session message", MONDAY));
        model.requests.clear();

        loop.runAndWait(request("hello", Scope.of("acme", "alice", "tuesday")));

        assertThat(model.requests.getFirst().getHistory()).noneMatch(m -> "first session message".equals(m.getContent()));
    }

    @Test
    void explicitHistoryIsUsedInsteadOfTheStoreAndNothingIsSaved() {
        AgentLoop loop = model.loop().conversations(store).build();

        loop.runAndWait(request("What did I say?", MONDAY)
                .history(List.of(Message.user("My name is Alice"), Message.assistant("Hi Alice"))));

        assertThat(model.requests.getFirst().getHistory()).extracting(Message::getContent).startsWith("My name is Alice", "Hi Alice");
        assertThat(store.load(MONDAY)).isEmpty();
    }

    @Test
    void runsWithoutAScopeDontTouchTheStore() {
        AgentLoop loop = model.loop().conversations(store).build();

        loop.runAndWait(LoopRequest.builder().prompt("hi").agentProfile(RECURSIVE));

        assertThat(model.requests.getFirst().getHistory()).isEmpty();
    }

    @Test
    void aFailedTurnIsNotSaved() {
        AgentLoop loop = model.loop().conversations(store).build();
        model.respond(request -> ScriptedModel.toolCall("x", "no_such_tool", Map.of()));

        assertThatThrownBy(() -> loop.runAndWait(request("do it", MONDAY))).isInstanceOf(UnregisteredToolException.class);
        assertThat(store.load(MONDAY)).isEmpty();
    }

    @Test
    void theSavedUserTurnNotesWhereAttachmentsWereSaved() {
        AgentLoop loop = model.loop().conversations(store).workspace(new InMemoryWorkspace()).build();

        loop.runAndWait(request("Summarise this", MONDAY).attachments(List.of(InputFile.of("notes.txt", "x".getBytes()))));

        assertThat(store.load(MONDAY).getFirst().getContent()).isEqualTo("Summarise this\n\n[Attached: uploads/notes.txt]");
    }

    @Test
    void historyIsAlsoPrefixedToPlanningCalls() {
        AgentLoop loop = model.loop().conversations(store).build();
        loop.runAndWait(request("We sell widgets", MONDAY));
        model.requests.clear();
        model.respond(request -> request.getPrompt().contains("Produce an ordered list of steps")
                ? com.manishpateluk.llmrouter.model.Response.builder()
                        .content("{\"summary\":\"s\",\"steps\":[{\"description\":\"step\"}]}").build()
                : complete("ok"));

        loop.runAndWait(LoopRequest.builder().prompt("Plan a launch").scope(MONDAY)
                .agentProfile(AgentProfile.builder().planMode(PlanMode.ALWAYS_PLAN).build()));

        Request planning = model.requests.getFirst();
        assertThat(planning.getHistory()).anyMatch(m -> "We sell widgets".equals(m.getContent()));
    }

    private static LoopRequest.LoopRequestBuilder request(String prompt, Scope scope) {
        return LoopRequest.builder().prompt(prompt).scope(scope).agentProfile(RECURSIVE);
    }
}
