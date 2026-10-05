package io.github.manishpateluk.llmagentloop.agent;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.Role;
import com.manishpateluk.llmrouter.model.ToolCall;
import com.manishpateluk.llmrouter.model.ToolDefinition;
import com.manishpateluk.llmrouter.model.Usage;
import com.manishpateluk.llmrouter.provider.Provider;
import com.manishpateluk.llmrouter.provider.ProviderAdapter;
import io.github.manishpateluk.llmagentloop.AgentLoop;
import io.github.manishpateluk.llmagentloop.AgentLoopResult;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.ScopeLevel;
import io.github.manishpateluk.llmagentloop.memory.InMemoryMemoryStore;
import io.github.manishpateluk.llmagentloop.memory.MemoryEntry;
import io.github.manishpateluk.llmagentloop.skill.Skills;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.workspace.InMemoryWorkspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeTest {

    private static final String MODEL = "test-agent-runtime-model";
    private static final Scope ALICE = Scope.of("acme", "alice", "s1");

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final InMemoryMemoryStore memory = new InMemoryMemoryStore();
    private Function<Request, Response> responder;
    private AgentRuntime runtime;

    @BeforeEach
    void setUp() {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.ANTHROPIC).model(MODEL)
                .contextWindowTokens(100_000).maxOutputTokens(4_000)
                .supportsTools(true).supportsStructuredOutput(true)
                .build());
        ProviderAdapter adapter = new ProviderAdapter() {
            @Override
            public Provider id() {
                return Provider.ANTHROPIC;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Response send(String model, Request request) {
                requests.add(request);
                Response response = responder.apply(request);
                return response.toBuilder().usage(Usage.builder().inputTokens(1).outputTokens(1).build()).build();
            }
        };
        RegisteredTool shared = new RegisteredTool(
                ToolDefinition.builder().name("company_lookup").description("Shared by every agent").parameters(Map.of("type", "object")).build(),
                (args, context) -> "Acme Ltd");
        runtime = new AgentRuntime(AgentLoop.builder()
                .router(new LlmRouter(List.of(adapter)))
                .tools(new ToolRegistry().register(shared))
                .memory(memory)
                .workspace(new InMemoryWorkspace())
                .build());
    }

    @AfterEach
    void tearDown() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, MODEL);
    }

    @Test
    void runsTheAgentWithItsInstructionsSkillsAndTools() {
        RegisteredTool own = new RegisteredTool(
                ToolDefinition.builder().name("crm_find").description("Agent's own tool").parameters(Map.of("type", "object")).build(),
                (args, context) -> "found");
        Agent agent = Agent.builder("---\nname: assistant\nplan_mode: recursive\n---\nYou are the assistant.")
                .skills(Skills.memory(), Skills.spreadsheets())
                .tool(own)
                .build();
        responder = request -> complete("done");

        AgentLoopResult result = runtime.runAndWait(agent, "hello", ALICE);

        assertThat(result.finalResponse().getContent()).isEqualTo("done");
        Request sent = requests.getFirst();
        assertThat(sent.getSystemInstructions()).startsWith("You are the assistant.").contains("### Memory", "### Spreadsheets");
        assertThat(sent.getTools()).extracting(ToolDefinition::getName).contains(
                "company_lookup", "crm_find", "memory_save", "spreadsheet_create", "workspace_write", "report_complete");
    }

    @Test
    void theSameAgentServesManyUsersWithSeparateMemory() {
        Agent agent = Agent.builder("---\nname: assistant\nplan_mode: recursive\n---\nHelp the user.")
                .skills(Skills.memory()).build();

        responder = request -> lastToolResult(request) == null
                ? toolCall("memory_save", Map.of("content", "Prefers annual billing"))
                : complete("saved");
        runtime.runAndWait(agent, "remember I prefer annual billing", ALICE);

        assertThat(memory.search(ALICE.atLevel(ScopeLevel.USER), "annual billing", 5)).extracting(MemoryEntry::content)
                .containsExactly("Prefers annual billing");
        assertThat(memory.search(Scope.of("acme", "bob", "s1").atLevel(ScopeLevel.USER), "", 5)).isEmpty();
    }

    @Test
    void agentsDelegateToEachOtherOnTheSameRuntimeAndScope() {
        Agent legal = Agent.builder("---\nname: legal\ndescription: Contract review\nplan_mode: recursive\n---\nYou review contracts.").build();
        Agent assistant = Agent.builder("---\nname: assistant\nplan_mode: recursive\n---\nYou are the assistant.")
                .delegateTo(legal).build();

        responder = request -> {
            if (request.getSystemInstructions().startsWith("You review contracts.")) {
                return complete("Clause 4 is risky.");
            }
            String last = lastToolResult(request);
            return last == null
                    ? toolCall("delegate_to_agent", Map.of("agent", "legal", "task", "Review the NDA"))
                    : complete("Legal says: " + last);
        };

        AgentLoopResult result = runtime.runAndWait(assistant, "check the NDA", ALICE);

        assertThat(result.finalResponse().getContent()).isEqualTo("Legal says: Clause 4 is risky.");
        assertThat(requests.getFirst().getTools()).anyMatch(t -> t.getName().equals("delegate_to_agent")
                && t.getDescription().contains("legal: Contract review"));
    }

    @Test
    void asyncRunsReturnAHandleThatCancelsThem() throws InterruptedException {
        java.util.concurrent.CountDownLatch toolStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<AgentLoopResult> result = new java.util.concurrent.atomic.AtomicReference<>();
        RegisteredTool slow = new RegisteredTool(
                ToolDefinition.builder().name("slow").description("x").parameters(Map.of("type", "object")).build(),
                (args, context) -> {
                    toolStarted.countDown();
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return "never";
                });
        Agent agent = Agent.builder("---\nname: worker\nplan_mode: recursive\n---\nWork.").tool(slow).build();
        responder = request -> toolCall("slow", Map.of());

        io.github.manishpateluk.llmagentloop.RunHandle handle = runtime.run(agent, "go", ALICE, r -> {
            result.set(r);
            done.countDown();
        }, e -> done.countDown());
        assertThat(toolStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        handle.cancel();

        assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().execution().terminationReason())
                .isEqualTo(io.github.manishpateluk.llmagentloop.execution.TerminationReason.CANCELLED);
    }

    @Test
    void eachAgentGetsOneLoopReusedAcrossRuns() {
        Agent agent = Agent.builder("---\nname: a\n---\nx").build();

        assertThat(runtime.loopFor(agent)).isSameAs(runtime.loopFor(agent));
        assertThat(runtime.loopFor(agent)).isNotSameAs(runtime.loopFor(Agent.builder("---\nname: b\n---\nx").build()));
    }

    private static String lastToolResult(Request request) {
        List<Message> history = request.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getRole() == Role.TOOL) {
                return history.get(i).getContent();
            }
        }
        return null;
    }

    private static Response toolCall(String name, Map<String, Object> arguments) {
        return Response.builder().content("")
                .toolCalls(List.of(ToolCall.builder().id(name + "-1").name(name).arguments(arguments).build()))
                .build();
    }

    private static Response complete(String answer) {
        return toolCall("report_complete", Map.of("finalAnswer", answer));
    }
}
