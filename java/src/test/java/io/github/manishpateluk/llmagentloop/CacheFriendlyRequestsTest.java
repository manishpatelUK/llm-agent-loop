package io.github.manishpateluk.llmagentloop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.manishpateluk.llmagentloop.conversation.InMemoryConversationStore;
import io.github.manishpateluk.llmagentloop.memory.InMemoryMemoryStore;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.testing.TestRuns;
import io.github.manishpateluk.llmagentloop.testing.TestRuns.TestRun;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmagentloop.tool.builtin.MemoryTools;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;

import org.junit.jupiter.api.Test;

/**
 * What a run sends is built to be read back from providers' prompt caches: each working call starts with exactly
 * what the previous one sent (tools, system instructions, conversation), the goal travels once, and what changes
 * from call to call (recalled memory, limit warnings) stays in the prompt, which no later call repeats.
 */
class CacheFriendlyRequestsTest {

    private static final Scope ALICE = Scope.of("acme", "alice", "s1");
    private static final ToolRegistry TOOLS = new ToolRegistry()
            .register(tool("search"), args -> "result for " + args.get("q"))
            .register(tool("fetch"), args -> "page " + args.get("url"))
            .register(tool("analyse"), args -> "analysis");

    private static ToolDefinition tool(String name) {
        return ToolDefinition.builder().name(name).description("Tool " + name)
                .parameters(Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")))).build();
    }

    /** Asserts every working call (one offering tools) of {@code requests} extends the one before it. */
    private static void assertEachCallExtendsThePrevious(List<Request> requests) {
        List<Request> steps = requests.stream().filter(r -> !r.getTools().isEmpty()).toList();
        assertThat(steps).hasSizeGreaterThan(1);
        for (int i = 1; i < steps.size(); i++) {
            Request earlier = steps.get(i - 1);
            Request later = steps.get(i);
            assertThat(later.getSystemInstructions()).as("call %d: system instructions", i).isEqualTo(earlier.getSystemInstructions());
            assertThat(later.getTools()).as("call %d: tools", i).isEqualTo(earlier.getTools());
            assertThat(later.getHistory().size()).as("call %d: history only grows", i).isGreaterThan(earlier.getHistory().size());
            assertThat(later.getHistory().subList(0, earlier.getHistory().size()))
                    .as("call %d starts with call %d's conversation", i, i - 1).isEqualTo(earlier.getHistory());
        }
    }

    private static long occurrences(Request request, String text) {
        long count = countIn(request.getPrompt(), text) + countIn(request.getSystemInstructions(), text);
        for (Message message : request.getHistory()) {
            count += countIn(message.getContent(), text);
        }
        return count;
    }

    private static long countIn(String haystack, String needle) {
        if (haystack == null) {
            return 0;
        }
        long count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void aToolLoopSendsTheGoalOnceAndOnlyAppends() {
        String deck = "Slide text. ".repeat(3_400); // about 40,000 characters, like a pitch deck in the task
        MockModel model = new MockModel()
                .callTool("search", Map.of("q", "market"))
                .callTools(new java.util.LinkedHashMap<>(Map.of("fetch", Map.of("url", "a"), "analyse", Map.of())))
                .callTool("search", Map.of("q", "competitors"))
                .reply("Report.");

        TestRun run = TestRuns.run(model.loopBuilder().tools(TOOLS).build(), LoopRequest.builder()
                .prompt("Research this company.\n\n" + deck).scope(ALICE).agentProfile(MockModel.STEP_BY_STEP));

        assertThat(run.answer()).isEqualTo("Report.");
        List<Request> requests = model.requests();
        assertThat(requests).hasSize(4);
        assertEachCallExtendsThePrevious(requests);
        assertThat(requests).allSatisfy(r -> assertThat(occurrences(r, deck)).as("the deck travels once per call").isEqualTo(1));
        assertThat(requests.getFirst().getPrompt()).startsWith("Research this company.");
        assertThat(requests.subList(1, 4)).allSatisfy(r -> {
            assertThat(r.getPrompt()).isEqualTo(AgentLoop.CONTINUE_PROMPT);
            assertThat(r.getHistory().getFirst().getContent()).startsWith("Research this company.");
            assertThat(MockModel.goal(r)).startsWith("Research this company.");
        });
    }

    @Test
    void toolsAreOfferedInOneStableOrder() {
        MockModel model = new MockModel().callTool("search", Map.of("q", "x")).reply("done");

        TestRuns.run(model.loopBuilder().tools(TOOLS).build(), LoopRequest.builder().prompt("go").agentProfile(MockModel.STEP_BY_STEP));

        List<String> offered = MockModel.toolsOffered(model.requests().getFirst());
        List<String> registered = offered.subList(0, 3);
        assertThat(registered).isSorted().containsExactly("analyse", "fetch", "search");
        assertThat(MockModel.toolsOffered(model.requests().get(1))).isEqualTo(offered);
    }

    @Test
    void planStepsExtendTheConversationOfTheStepsBeforeThem() {
        MockModel model = new MockModel()
                .planCheck(true)
                .plan("Gather data", "Write it up")
                .callTool("search", Map.of("q", "data"))
                .reply("Data gathered.")
                .callTool("analyse", Map.of())
                .reply("Final report.");

        TestRun run = TestRuns.run(model.loopBuilder().tools(TOOLS).build(), LoopRequest.builder().prompt("Write a report")
                .agentProfile(AgentProfile.builder().planMode(PlanMode.AUTO).build()));

        assertThat(run.answer()).isEqualTo("Final report.");
        List<Request> steps = model.requests().stream().filter(r -> !r.getTools().isEmpty()).toList();
        assertThat(steps).hasSize(4);
        for (int i = 1; i < steps.size(); i++) {
            Request earlier = steps.get(i - 1);
            Request later = steps.get(i);
            assertThat(later.getHistory().subList(0, earlier.getHistory().size())).as("call %d", i).isEqualTo(earlier.getHistory());
            assertThat(later.getSystemInstructions()).isEqualTo(earlier.getSystemInstructions());
        }
        assertThat(steps).allSatisfy(r -> assertThat(occurrences(r, "Overall goal: Write a report")).isEqualTo(1));
        assertThat(steps.getLast().getHistory()).extracting(Message::getContent)
                .contains("Plan step 1 of 2: Gather data", "Completed: Gather data\n\nData gathered.");
        assertThat(steps.get(2).getPrompt()).isEqualTo("Plan step 2 of 2: Write it up");
    }

    @Test
    void aSubTaskExtendsItsParentsConversationAndThenTheParentCarriesOn() {
        MockModel model = new MockModel()
                .callTool("search", Map.of("q", "background"))
                .spawnSubTask("Draft the summary")
                .callTool("analyse", Map.of())
                .reply("Summary drafted.")
                .reply("Final report.");

        TestRun run = TestRuns.run(model.loopBuilder().tools(TOOLS).build(), LoopRequest.builder().prompt("Write a report")
                .agentProfile(MockModel.STEP_BY_STEP));

        assertThat(run.answer()).isEqualTo("Final report.");
        List<Request> requests = model.requests();
        assertThat(requests).hasSize(5);
        Request parentBefore = requests.get(1);
        Request subTaskFirst = requests.get(2);
        Request subTaskSecond = requests.get(3);
        Request parentAfter = requests.get(4);
        assertThat(subTaskFirst.getPrompt()).isEqualTo("Draft the summary");
        assertThat(subTaskFirst.getHistory()).as("the sub-task starts from its parent's conversation")
                .isEqualTo(parentBefore.getHistory());
        assertThat(subTaskSecond.getHistory().subList(0, subTaskFirst.getHistory().size())).isEqualTo(subTaskFirst.getHistory());
        assertThat(parentAfter.getHistory().subList(0, parentBefore.getHistory().size()))
                .as("the parent carries on from its own conversation").isEqualTo(parentBefore.getHistory());
        assertThat(parentAfter.getHistory().getLast().getContent()).isEqualTo("Summary drafted.");
    }

    @Test
    void recalledMemoryAndLimitWarningsStayInThePrompt() {
        InMemoryMemoryStore memory = new InMemoryMemoryStore();
        MockModel model = new MockModel()
                .callTool(MemoryTools.SAVE, Map.of("content", "the market is widgets"))
                .callTool("search", Map.of("q", "market"))
                .callTool("search", Map.of("q", "market widgets"))
                .reply("done");
        AgentLoop loop = model.loopBuilder().memory(memory)
                .tools(new ToolRegistry().registerAll(MemoryTools.all())
                        .register(tool("search"), args -> "result")).build();

        TestRuns.run(loop, LoopRequest.builder().prompt("Research the market").scope(ALICE)
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).maxSteps(4).build()));

        List<Request> requests = model.requests();
        assertEachCallExtendsThePrevious(requests);
        assertThat(requests).anySatisfy(r -> assertThat(r.getPrompt()).contains("Relevant memory", "the market is widgets"));
        assertThat(requests).anySatisfy(r -> assertThat(r.getPrompt()).startsWith(AgentLoop.LIMIT_WARNING_PREFIX));
        assertThat(requests).allSatisfy(r -> {
            assertThat(r.getSystemInstructions()).doesNotContain("Relevant memory", AgentLoop.LIMIT_WARNING_PREFIX);
            assertThat(r.getHistory()).noneMatch(m -> m.getContent().contains("Relevant memory")
                    || m.getContent().contains(AgentLoop.LIMIT_WARNING_PREFIX));
        });
    }

    @Test
    void chatTurnsShareTheirSystemInstructionsAndConversation() {
        MockModel model = new MockModel().reply("Hi Alice.").reply("Paris.");
        AgentLoop loop = model.loopBuilder().conversations(new InMemoryConversationStore()).build();

        TestRuns.run(loop, LoopRequest.builder().prompt("Hello").scope(ALICE).operatingContext("Plan: free")
                .agentProfile(MockModel.STEP_BY_STEP));
        TestRuns.run(loop, LoopRequest.builder().prompt("Capital of France?").scope(ALICE).operatingContext("Plan: paid")
                .agentProfile(MockModel.STEP_BY_STEP));

        Request first = model.requests().get(0);
        Request second = model.requests().get(1);
        assertThat(second.getSystemInstructions()).as("same marker id and no per-turn context in the instructions")
                .isEqualTo(first.getSystemInstructions()).doesNotContain("Plan: ");
        assertThat(second.getHistory()).extracting(Message::getRole, Message::getContent).contains(
                org.assertj.core.groups.Tuple.tuple(Role.USER, "Hello"),
                org.assertj.core.groups.Tuple.tuple(Role.ASSISTANT, "Hi Alice."));
        assertThat(second.getHistory()).filteredOn(m -> m.getRole() == Role.SYSTEM).extracting(Message::getContent)
                .singleElement().asString().contains("Plan: paid");
    }

    @Test
    void theMarkerIdIsSecretAndTheSameForEveryRunInAConversation() {
        Pattern id = Pattern.compile("\\[\\[untrusted-content ([0-9a-f]{8}) ");
        MockModel model = new MockModel().otherwise(request -> io.github.manishpateluk.llmrouter.model.Response.builder()
                .content("ok").build());
        String secret = "a-secret-of-sufficient-length";
        AgentLoop first = model.loopBuilder().untrustedMarkerSecret(secret).build();
        AgentLoop second = model.loopBuilder().untrustedMarkerSecret(secret).build();
        AgentLoop other = model.loopBuilder().build();

        TestRuns.run(first, LoopRequest.builder().prompt("a").scope(ALICE).agentProfile(MockModel.STEP_BY_STEP));
        TestRuns.run(first, LoopRequest.builder().prompt("b").scope(ALICE).agentProfile(MockModel.STEP_BY_STEP));
        TestRuns.run(second, LoopRequest.builder().prompt("c").scope(ALICE).agentProfile(MockModel.STEP_BY_STEP));
        TestRuns.run(first, LoopRequest.builder().prompt("d").scope(Scope.of("acme", "alice", "s2")).agentProfile(MockModel.STEP_BY_STEP));
        TestRuns.run(other, LoopRequest.builder().prompt("e").scope(ALICE).agentProfile(MockModel.STEP_BY_STEP));
        TestRuns.run(first, LoopRequest.builder().prompt("f").agentProfile(MockModel.STEP_BY_STEP));
        TestRuns.run(first, LoopRequest.builder().prompt("g").agentProfile(MockModel.STEP_BY_STEP));

        List<String> ids = new ArrayList<>();
        for (Request request : model.requests()) {
            Matcher matcher = Pattern.compile("\\[\\[untrusted-content ([0-9a-f]{8}) \\.\\.\\.]]").matcher(request.getSystemInstructions());
            assertThat(matcher.find()).isTrue();
            ids.add(matcher.group(1));
        }
        assertThat(ids.get(1)).as("same conversation, same loop").isEqualTo(ids.get(0));
        assertThat(ids.get(2)).as("same conversation, another instance with the same secret").isEqualTo(ids.get(0));
        assertThat(ids.get(3)).as("another conversation").isNotEqualTo(ids.get(0));
        assertThat(ids.get(4)).as("another loop's own random secret").isNotEqualTo(ids.get(0));
        assertThat(ids.get(6)).as("runs without a scope are their own conversation").isNotEqualTo(ids.get(5));
        assertThat(id.pattern()).isNotEmpty();
    }

    @Test
    void aShortMarkerSecretIsRefused() {
        assertThatThrownBy(() -> AgentLoop.builder().untrustedMarkerSecret("too short"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 16");
    }

    @Test
    void anAttachedImageRidesWithTheGoalNotWithEveryPrompt() {
        MockModel model = new MockModel().callTool("search", Map.of("q", "x")).callTool("search", Map.of("q", "y")).reply("done");

        TestRuns.run(model.loopBuilder().tools(TOOLS).build(), LoopRequest.builder().prompt("Describe the chart")
                .attachments(List.of(InputFile.of("chart.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'})))
                .agentProfile(MockModel.STEP_BY_STEP));

        List<Request> requests = model.requests();
        assertThat(requests.getFirst().getAttachments()).hasSize(1);
        assertThat(requests.subList(1, 3)).allSatisfy(r -> {
            assertThat(r.getAttachments()).isEmpty();
            assertThat(r.getHistory().getFirst().getAttachments()).hasSize(1);
            assertThat(r.everyAttachment()).hasSize(1);
        });
        assertEachCallExtendsThePrevious(requests);
        assertThat(Instant.now()).isNotNull();
    }
}
