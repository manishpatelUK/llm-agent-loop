package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.AgentLoopRunSupport.Capture;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.AgentDelegate;
import io.github.manishpateluk.llmagentloop.tool.builtin.DelegationTools;
import io.github.manishpateluk.llmagentloop.tool.builtin.HumanTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.github.manishpateluk.llmagentloop.AgentLoopRunSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

class HumanAndDelegationToolsTest {

    private static final String MODEL = "test-human-delegation-model";
    private static final AgentProfile RECURSIVE = AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build();
    private static final Scope ALICE = Scope.of("acme", "alice", "s1");

    @BeforeEach
    void registerModel() {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.ANTHROPIC).model(MODEL)
                .contextWindowTokens(100_000).maxOutputTokens(4_000)
                .supportsTools(true).supportsStructuredOutput(true)
                .build());
    }

    @AfterEach
    void removeModel() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, MODEL);
    }

    @Test
    void askHumanBlocksForTheAnswerAndFeedsItBack() throws InterruptedException {
        AtomicReference<HumanTools.Question> asked = new AtomicReference<>();
        AtomicReference<Scope> askedFor = new AtomicReference<>();
        List<Request> requests = new CopyOnWriteArrayList<>();
        AgentLoop loop = loop(request -> {
            requests.add(request);
            return requests.size() == 1
                    ? toolCall("h1", "ask_human", Map.of("question", "Monthly or annual billing?", "options", List.of("monthly", "annual")))
                    : complete("going annual");
        }, new ToolRegistry().register(HumanTools.askHuman((question, context) -> {
            asked.set(question);
            askedFor.set(context.scope());
            return Optional.of("annual");
        })));

        Capture capture = run(loop, LoopRequest.builder().prompt("set up billing").agentProfile(RECURSIVE).scope(ALICE));

        assertThat(capture.error()).isNull();
        assertThat(asked.get().options()).containsExactly("monthly", "annual");
        assertThat(askedFor.get()).isEqualTo(ALICE);
        assertThat(lastToolResult(requests.get(1))).isEqualTo("The user answered: annual");
        assertThat(capture.messages()).anyMatch(m -> m.message().startsWith("Waiting for the user"));
    }

    @Test
    void askHumanWithoutAnAnswerTellsTheModelToCarryOn() throws InterruptedException {
        List<Request> requests = new CopyOnWriteArrayList<>();
        AgentLoop loop = loop(request -> {
            requests.add(request);
            return requests.size() == 1 ? toolCall("h1", "ask_human", Map.of("question", "?")) : complete("ok");
        }, new ToolRegistry().register(HumanTools.askHuman((question, context) -> Optional.empty())));

        run(loop, LoopRequest.builder().prompt("x").agentProfile(RECURSIVE));

        assertThat(lastToolResult(requests.get(1))).startsWith("The user did not answer");
    }

    @Test
    void delegatesToAnotherAgentWithTheSameScopeAndForwardsItsProgress() throws InterruptedException {
        AtomicReference<Scope> legalSawScope = new AtomicReference<>();
        AtomicInteger legalCalls = new AtomicInteger();
        AgentLoop legal = loop(request -> legalCalls.incrementAndGet() == 1
                        ? toolCall("c1", "check_clause", Map.of())
                        : complete("Clause 4 is unenforceable."),
                new ToolRegistry().register(
                        ToolDefinition.builder().name("check_clause").description("x").parameters(Map.of("type", "object")).build(),
                        (args, context) -> {
                            legalSawScope.set(context.scope());
                            return "checked";
                        }));

        List<Request> parentRequests = new CopyOnWriteArrayList<>();
        AgentLoop assistant = loop(request -> {
            parentRequests.add(request);
            return parentRequests.size() == 1
                    ? toolCall("d1", "delegate_to_agent", Map.of("agent", "legal", "task", "Review clause 4 of the NDA"))
                    : complete("Legal says clause 4 needs rework.");
        }, new ToolRegistry().register(DelegationTools.delegateToAgent(List.of(
                AgentDelegate.of("legal", "Contract and compliance review", legal, RECURSIVE)))));

        Capture capture = run(assistant, LoopRequest.builder().prompt("check the NDA").agentProfile(RECURSIVE).scope(ALICE));

        assertThat(capture.error()).isNull();
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("Legal says clause 4 needs rework.");
        assertThat(lastToolResult(parentRequests.get(1))).isEqualTo("Clause 4 is unenforceable.");
        assertThat(legalSawScope.get()).isEqualTo(ALICE);
        assertThat(capture.messages()).anyMatch(m -> m.message().startsWith("[legal] "));
        assertThat(parentRequests.getFirst().getTools()).anyMatch(t -> t.getName().equals("delegate_to_agent")
                && t.getDescription().contains("legal: Contract and compliance review"));
    }

    @Test
    void delegationChainsAreCappedSoAgentsCannotRecurseForever() throws InterruptedException {
        AtomicInteger delegations = new AtomicInteger();
        AtomicReference<AgentLoop> self = new AtomicReference<>();
        // An agent whose only delegate is itself, resolved lazily since the loop doesn't exist yet.
        AgentDelegate toSelf = new AgentDelegate() {
            @Override
            public String name() {
                return "self";
            }

            @Override
            public String description() {
                return "this same agent";
            }

            @Override
            public String run(String task, ToolContext context) {
                return AgentDelegate.of("self", "this same agent", self.get(), RECURSIVE).run(task, context);
            }
        };
        self.set(loop(request -> {
            String last = lastToolResult(request);
            if (last != null) {
                return complete(last.startsWith("Error: Delegation is already") ? "stopped at the cap" : last);
            }
            delegations.incrementAndGet();
            return toolCall("d", "delegate_to_agent", Map.of("agent", "self", "task", "again"));
        }, new ToolRegistry().register(DelegationTools.delegateToAgent(List.of(toSelf)))));

        Capture capture = run(self.get(), LoopRequest.builder().prompt("go").agentProfile(RECURSIVE));

        assertThat(capture.error()).isNull();
        // The top-level run plus MAX_DEPTH nested runs each tried to delegate; the deepest attempt was refused.
        assertThat(delegations.get()).isEqualTo(DelegationTools.MAX_DEPTH + 1);
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("stopped at the cap");
    }

    private static AgentLoop loop(Function<Request, Response> responder, ToolRegistry tools) {
        return AgentLoop.builder()
                .router(new LlmRouter(List.of(new FakeProviderAdapter(responder))))
                .tools(tools == null ? new ToolRegistry() : tools)
                .build();
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
