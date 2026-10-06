package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmagentloop.conversation.InMemoryConversationStore;
import io.github.manishpateluk.llmagentloop.memory.InMemoryMemoryStore;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.testing.TestRuns;
import io.github.manishpateluk.llmagentloop.testing.TestRuns.TestRun;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.MemoryTools;
import io.github.manishpateluk.llmagentloop.tool.builtin.WorkspaceTools;
import io.github.manishpateluk.llmagentloop.usage.InMemoryUsageMeter;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One agent serving many users at once: every run is in flight at the same moment, and each must
 * see only its own memory, files, conversation and usage.
 */
class ConcurrentRunsTest {

    private static final int USERS = 20;

    @Test
    void simultaneousRunsForDifferentUsersNeverSeeEachOthersData() throws Exception {
        CountDownLatch allInFlight = new CountDownLatch(USERS);
        ToolRegistry tools = new ToolRegistry()
                .registerAll(MemoryTools.all())
                .registerAll(WorkspaceTools.all())
                .register(ToolDefinition.builder().name("rendezvous").description("Waits until every run gets here")
                        .parameters(Map.of("type", "object")).build(), args -> {
                    allInFlight.countDown();
                    try {
                        return allInFlight.await(20, TimeUnit.SECONDS) ? "everyone is here" : "timed out";
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                });

        // The model's behaviour depends only on the request it sees, so runs can interleave freely.
        MockModel model = new MockModel().otherwise(ConcurrentRunsTest::respond);
        InMemoryConversationStore conversations = new InMemoryConversationStore();
        InMemoryWorkspace workspace = new InMemoryWorkspace();
        AgentLoop loop = model.loopBuilder().tools(tools).memory(new InMemoryMemoryStore()).workspace(workspace)
                .conversations(conversations).build();

        List<Future<TestRun>> runs = new ArrayList<>();
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < USERS; i++) {
                String user = "user" + i;
                runs.add(callers.submit(() -> TestRuns.run(loop, LoopRequest.builder()
                        .prompt(user).agentProfile(MockModel.STEP_BY_STEP)
                        .scope(Scope.of("acme", user, "session-" + user)), Duration.ofSeconds(60))));
            }
        }

        // Every run reached the rendezvous, so all of them were in flight at the same time.
        assertThat(allInFlight.getCount()).isZero();
        InMemoryUsageMeter meter = (InMemoryUsageMeter) loop.usageMeter();
        for (int i = 0; i < USERS; i++) {
            String user = "user" + i;
            TestRun run = runs.get(i).get();
            assertThat(run.error()).as(user).isNull();
            // Each user read back exactly their own note and memory, and nobody else's.
            assertThat(run.answer()).as(user).contains("note of " + user + ".", "fact about " + user + ".");
            for (int j = 0; j < USERS; j++) {
                if (j != i) {
                    assertThat(run.answer()).as(user).doesNotContain("user" + j + ".");
                }
            }
            Scope scope = Scope.of("acme", user, "session-" + user);
            assertThat(workspace.read(scope.atLevel(ScopeLevel.USER), "note.md")).get()
                    .satisfies(file -> assertThat(file.text()).isEqualTo("note of " + user + "."));
            assertThat(conversations.load(scope)).extracting(Message::getContent).first().isEqualTo(user);
            assertThat(meter.totals(scope.atLevel(ScopeLevel.USER)).calls()).as(user).isEqualTo(3);
            assertThat(run.result().usage().calls()).isEqualTo(3);
        }
        assertThat(meter.totals(Scope.of("acme", "user0", "x").atLevel(ScopeLevel.TENANT)).calls()).isEqualTo(3L * USERS);
    }

    /**
     * Call 1: save a note and a memory, and meet the other runs. Call 2: read them back. Call 3:
     * answer with what was read. The user is whatever the prompt says.
     */
    private static Response respond(Request request) {
        String user = request.getPrompt().replace("Continue toward the goal: ", "");
        List<String> results = request.getHistory().stream()
                .filter(message -> message.getRole() == Role.TOOL).map(Message::getContent).toList();
        if (results.isEmpty()) {
            return toolCalls(
                    call("w-" + user, WorkspaceTools.WRITE, Map.of("path", "note.md", "content", "note of " + user + ".")),
                    call("m-" + user, MemoryTools.SAVE, Map.of("content", "fact about " + user + ".")),
                    call("r-" + user, "rendezvous", Map.of()));
        }
        if (results.size() == 3) {
            return toolCalls(
                    call("rw-" + user, WorkspaceTools.READ, Map.of("path", "note.md")),
                    call("rm-" + user, MemoryTools.SEARCH, Map.of("query", "fact about")));
        }
        return Response.builder().content(String.join("\n", results.subList(3, results.size()))).build();
    }

    private static ToolCall call(String id, String name, Map<String, Object> arguments) {
        return ToolCall.builder().id(id).name(name).arguments(arguments).build();
    }

    private static Response toolCalls(ToolCall... calls) {
        return Response.builder().content("").toolCalls(List.of(calls)).build();
    }
}
