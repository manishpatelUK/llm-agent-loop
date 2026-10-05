package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.AgentLoopRunSupport.Capture;
import io.github.manishpateluk.llmagentloop.memory.InMemoryMemoryStore;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.MemoryTools;
import io.github.manishpateluk.llmagentloop.tool.builtin.WorkspaceTools;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.manishpateluk.llmagentloop.AgentLoopRunSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/** One agent definition, many users: memory and workspace must follow the run's {@link Scope}. */
class AgentLoopScopeTest {

    private static final String MODEL = "test-agent-loop-scope-model";
    private static final AgentProfile RECURSIVE =
            AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build();

    private final InMemoryMemoryStore memory = new InMemoryMemoryStore();
    private final InMemoryWorkspace workspace = new InMemoryWorkspace();
    private final Deque<Response> script = new ArrayDeque<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private AgentLoop loop;

    @BeforeEach
    void setUp() {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.ANTHROPIC).model(MODEL)
                .contextWindowTokens(100_000).maxOutputTokens(4_000)
                .supportsTools(true).supportsStructuredOutput(true)
                .build());
        LlmRouter router = new LlmRouter(List.of(new FakeProviderAdapter(request -> {
            requests.add(request);
            return script.removeFirst();
        })));
        loop = AgentLoop.builder()
                .router(router)
                .tools(new ToolRegistry().registerAll(MemoryTools.all()).registerAll(WorkspaceTools.all()))
                .memory(memory)
                .workspace(workspace)
                .build();
    }

    @AfterEach
    void tearDown() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, MODEL);
    }

    @Test
    void memorySavedInOneSessionIsRecalledInTheSameUsersNextSessionButNeverForAnotherUser() throws InterruptedException {
        script.add(toolCall("m1", "memory_save", Map.of("content", "Alice's company sells accounting software")));
        script.add(complete("noted"));
        assertThat(run(loop, request("remember this", Scope.of("acme", "alice", "monday"))).error()).isNull();

        requests.clear();
        script.add(complete("answer"));
        run(loop, request("what does my company sell? accounting", Scope.of("acme", "alice", "tuesday")));
        assertThat(recalledMemory(requests.getFirst())).contains("Alice's company sells accounting software");

        requests.clear();
        script.add(complete("answer"));
        run(loop, request("what does my company sell? accounting", Scope.of("acme", "bob", "tuesday")));
        assertThat(recalledMemory(requests.getFirst())).isEmpty();
    }

    @Test
    void runsWithoutAScopeShareNothingWithEachOther() throws InterruptedException {
        script.add(toolCall("m1", "memory_save", Map.of("content", "unscoped accounting fact")));
        script.add(complete("noted"));
        run(loop, request("remember this", null));

        requests.clear();
        script.add(complete("answer"));
        run(loop, request("accounting", null));

        assertThat(recalledMemory(requests.getFirst())).isEmpty();
    }

    @Test
    void workspaceFilesPersistForTheUserAndTheResultListsWhatTheRunChanged() throws InterruptedException {
        script.add(toolCall("w1", "workspace_write", Map.of("path", "/plans/launch.md", "content", "Launch in March")));
        script.add(complete("written"));
        Capture capture = run(loop, request("draft a plan", Scope.of("acme", "alice", "monday")));

        assertThat(capture.result().changedFiles()).containsExactly("plans/launch.md");
        Scope aliceFiles = new Scope("acme", "alice", null);
        assertThat(workspace.scopedTo(aliceFiles, WorkspaceLimits.DEFAULT).require("plans/launch.md").text())
                .isEqualTo("Launch in March");
        assertThat(workspace.scopedTo(new Scope("acme", "bob", null), WorkspaceLimits.DEFAULT).read("plans/launch.md"))
                .isEmpty();
    }

    @Test
    void aFixableToolErrorGoesBackToTheModelAndTheRunCarriesOn() throws InterruptedException {
        script.add(toolCall("w1", "workspace_read", Map.of("path", "../../etc/passwd")));
        script.add(complete("couldn't read it"));

        Capture capture = run(loop, request("read that file", Scope.of("acme", "alice", "s1")));

        assertThat(capture.error()).isNull();
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("couldn't read it");
        Message toolResult = requests.get(1).getHistory().stream()
                .filter(m -> m.getRole() == Role.TOOL).findFirst().orElseThrow();
        assertThat(toolResult.getToolCallId()).isEqualTo("w1");
        assertThat(toolResult.getContent()).startsWith("Error: ").contains("..");
        assertThat(capture.messages()).anyMatch(m -> m.type() == MessageType.WARNING && m.message().contains("workspace_read"));
    }

    @Test
    void callsThatOfferToolsRequireAToolCapableModel() throws InterruptedException {
        script.add(complete("done"));

        run(loop, request("anything", Scope.of("acme", "alice", "s1")));

        assertThat(requests.getFirst().getConfig().getRequiredFeatures()).contains(Feature.TOOLS);
    }

    private static LoopRequest.LoopRequestBuilder request(String prompt, Scope scope) {
        return LoopRequest.builder().prompt(prompt).agentProfile(RECURSIVE).scope(scope);
    }

    /** The loop adds auto-recalled memory to a step's history as a system message. */
    private static String recalledMemory(Request request) {
        return request.getHistory().stream()
                .filter(m -> m.getRole() == Role.SYSTEM && m.getContent().startsWith("Relevant memory"))
                .map(Message::getContent)
                .findFirst().orElse("");
    }

    private static Response toolCall(String id, String name, Map<String, Object> arguments) {
        return Response.builder()
                .content("")
                .toolCalls(List.of(ToolCall.builder().id(id).name(name).arguments(arguments).build()))
                .build();
    }

    private static Response complete(String answer) {
        return toolCall("done", "report_complete", Map.of("finalAnswer", answer));
    }
}
