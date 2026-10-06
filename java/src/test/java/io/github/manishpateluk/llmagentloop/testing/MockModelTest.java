package io.github.manishpateluk.llmagentloop.testing;

import io.github.manishpateluk.llmagentloop.AgentLoop;
import io.github.manishpateluk.llmagentloop.AgentProfile;
import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.MessageType;
import io.github.manishpateluk.llmagentloop.PlanMode;
import io.github.manishpateluk.llmagentloop.testing.TestRuns.TestRun;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockModelTest {

    private static final ToolRegistry WEATHER = new ToolRegistry().register(
            ToolDefinition.builder().name("get_weather").description("Weather for a city").parameters(Map.of("type", "object")).build(),
            args -> args.get("city") + ": 18C, sunny");

    @Test
    void scriptsAToolCallThenAnAnswerAndRecordsWhatTheModelSaw() {
        MockModel model = new MockModel()
                .callTool("get_weather", Map.of("city", "Paris"))
                .reply("It's 18C and sunny in Paris.");
        AgentLoop loop = model.loopBuilder().tools(WEATHER).build();

        TestRun run = TestRuns.run(loop, LoopRequest.builder().prompt("Weather in Paris?").agentProfile(MockModel.STEP_BY_STEP));

        assertThat(run.answer()).isEqualTo("It's 18C and sunny in Paris.");
        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests().getFirst().getPrompt()).isEqualTo("Weather in Paris?");
        assertThat(MockModel.toolsOffered(model.requests().getFirst())).contains("get_weather");
        assertThat(MockModel.lastToolResult(model.requests().get(1))).isEqualTo("Paris: 18C, sunny");
        assertThat(run.messages(MessageType.TOOL_CALL)).containsExactly("Calling tool: get_weather");
        assertThat(model.remaining()).isZero();
    }

    @Test
    void streamsTheAnswerWordByWord() {
        MockModel model = new MockModel().reply("Hello there, Alice.");

        TestRun run = TestRuns.run(model.loopBuilder().build(),
                LoopRequest.builder().prompt("hi").agentProfile(MockModel.STEP_BY_STEP));

        assertThat(run.streamed()).containsExactly("Hello ", "there, ", "Alice.");
        assertThat(run.shownAnswer()).isEqualTo(run.answer());
    }

    @Test
    void scriptsPlanningCalls() {
        MockModel model = new MockModel()
                .planCheck(true)
                .plan("Research \"competitors\"", "Write the summary")
                .reply("Found three.")
                .reply("Summary written.");

        TestRun run = TestRuns.run(model.loopBuilder().build(),
                LoopRequest.builder().prompt("Competitor report").agentProfile(AgentProfile.builder().planMode(PlanMode.AUTO).build()));

        assertThat(run.answer()).isEqualTo("Summary written.");
        assertThat(model.requests()).hasSize(4);
        assertThat(model.requests().get(2).getPrompt()).contains("Research \"competitors\"");
    }

    @Test
    void severalToolCallsInOneTurnAndSubTasks() {
        Map<String, Map<String, Object>> both = new LinkedHashMap<>();
        both.put("get_weather", Map.of("city", "Paris"));
        MockModel model = new MockModel()
                .spawnSubTask("check Paris")
                .callTools(both)
                .reply("Paris done.")
                .reply("All done.");

        TestRun run = TestRuns.run(model.loopBuilder().tools(WEATHER).build(),
                LoopRequest.builder().prompt("go").agentProfile(MockModel.STEP_BY_STEP));

        assertThat(run.answer()).isEqualTo("All done.");
        assertThat(model.requests().get(1).getPrompt()).isEqualTo("check Paris");
    }

    @Test
    void runningOutOfScriptFailsTheRunWithAClearMessage() {
        MockModel model = new MockModel().callTool("get_weather", Map.of("city", "Paris"));

        TestRun run = TestRuns.run(model.loopBuilder().tools(WEATHER).build(),
                LoopRequest.builder().prompt("go").agentProfile(MockModel.STEP_BY_STEP));

        assertThat(run.result()).isNull();
        assertThat(run.error()).hasStackTraceContaining("MockModel ran out of scripted replies at call 2");
        assertThatThrownBy(run::answer).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFallbackAnswersUnscriptedCallsAndCustomRespondersSeeTheRequest() {
        MockModel model = new MockModel()
                .respond(request -> Response.builder().content("echo: " + request.getPrompt()).build())
                .otherwise(request -> Response.builder().content("fallback").build());
        AgentLoop loop = model.loopBuilder().build();

        assertThat(TestRuns.run(loop, LoopRequest.builder().prompt("one").agentProfile(MockModel.STEP_BY_STEP)).answer())
                .isEqualTo("echo: one");
        assertThat(TestRuns.run(loop, LoopRequest.builder().prompt("two").agentProfile(MockModel.STEP_BY_STEP)).answer())
                .isEqualTo("fallback");
    }

    @Test
    void reportsUsageForMeteringTests() {
        MockModel model = new MockModel().usage(120, 30).reply("ok");

        TestRun run = TestRuns.run(model.loopBuilder().build(), LoopRequest.builder().prompt("hi").agentProfile(MockModel.STEP_BY_STEP),
                Duration.ofSeconds(10));

        assertThat(run.result().usage().inputTokens()).isEqualTo(120);
        assertThat(run.result().usage().outputTokens()).isEqualTo(30);
    }
}
