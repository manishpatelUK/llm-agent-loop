package io.github.manishpateluk.llmagentloop;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.manishpateluk.llmagentloop.execution.StepAction;
import io.github.manishpateluk.llmagentloop.execution.StepRecord;
import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.testing.MockModel;
import io.github.manishpateluk.llmagentloop.testing.TestRuns;
import io.github.manishpateluk.llmagentloop.testing.TestRuns.TestRun;
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.anthropic.AnthropicAdapter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The loop's own model calls are capped, and a plan or plan check that goes wrong never fails the run. */
class HousekeepingLimitsTest {

    private static final AgentProfile ALWAYS_PLAN = AgentProfile.builder().planMode(PlanMode.ALWAYS_PLAN).build();
    private static final AgentProfile AUTO = AgentProfile.builder().planMode(PlanMode.AUTO).build();

    /** A plan cut off part-way through "steps", as when a call runs into its output cap. */
    private static Response truncatedPlan(int outputTokens) {
        return Response.builder()
                .content("{\"summary\":\"A long plan\",\"steps\":[{\"description\":\"Gather the figures\"},{\"descr")
                .usage(Usage.builder().inputTokens(50).outputTokens(outputTokens).build())
                .build();
    }

    @Test
    void aPlanCutOffAtItsOutputCapLeadsToACompletedRunWithoutAPlan() {
        MockModel model = new MockModel()
                .respond(request -> truncatedPlan(request.getMaxOutputTokens()))
                .reply("The answer, worked out step by step.");

        TestRun run = TestRuns.run(model.loopBuilder().build(),
                LoopRequest.builder().prompt("Do a big thing").agentProfile(ALWAYS_PLAN));

        assertThat(run.error()).isNull();
        assertThat(run.result().execution().terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(run.answer()).isEqualTo("The answer, worked out step by step.");
        assertThat(run.messages(MessageType.WARNING)).containsExactly("Couldn't make a plan (the reply was cut off at its "
                + "8000-token output limit); working toward the goal step by step instead.");
        assertThat(run.result().execution().steps()).extracting(StepRecord::action)
                .containsExactly(StepAction.PLAN_FAILED, StepAction.COMPLETE);
        assertThat(run.result().execution().steps().getFirst().response()).isNotNull();
        // The failed call still counts, and is metered.
        assertThat(run.result().usage().calls()).isEqualTo(2);
        assertThat(run.result().usage().outputTokens()).isEqualTo(8_000 + 5);
        // The step after it pursued the whole goal, with sub-tasks available, as if no plan were needed.
        assertThat(MockModel.toolsOffered(model.requests().get(1))).contains("spawn_sub_task");
        assertThat(model.requests().get(1).getPrompt()).isEqualTo("Do a big thing");
    }

    @Test
    void anEmptyPlanReplyLeadsToACompletedRunWithoutAPlan() {
        MockModel model = new MockModel()
                .respond(request -> Response.builder().content("").build())
                .reply("Done.");

        TestRun run = TestRuns.run(model.loopBuilder().build(),
                LoopRequest.builder().prompt("Do a thing").agentProfile(ALWAYS_PLAN));

        assertThat(run.error()).isNull();
        assertThat(run.result().execution().terminationReason()).isEqualTo(TerminationReason.COMPLETED);
        assertThat(run.answer()).isEqualTo("Done.");
        assertThat(run.messages(MessageType.WARNING)).containsExactly("Couldn't make a plan (the reply had no usable "
                + "structured output); working toward the goal step by step instead.");
    }

    @Test
    void aPlanCallThatFailsOutrightLeadsToACompletedRunWithoutAPlan() {
        MockModel model = new MockModel()
                .respond(request -> {
                    throw new IllegalStateException("provider unavailable");
                })
                .reply("Done anyway.");

        TestRun run = TestRuns.run(model.loopBuilder().build(),
                LoopRequest.builder().prompt("Do a thing").agentProfile(ALWAYS_PLAN));

        assertThat(run.error()).isNull();
        assertThat(run.answer()).isEqualTo("Done anyway.");
        assertThat(run.messages(MessageType.WARNING)).singleElement().asString()
                .startsWith("Couldn't make a plan (").contains("provider unavailable");
        assertThat(run.result().execution().steps().getFirst().action()).isEqualTo(StepAction.PLAN_FAILED);
    }

    @Test
    void aPlanWithMoreStepsThanAllowedIsNotUsed() {
        MockModel model = new MockModel().plan("one", "two", "three").reply("Done.");

        TestRun run = TestRuns.run(model.loopBuilder().build(), LoopRequest.builder().prompt("Do a thing")
                .agentProfile(ALWAYS_PLAN).housekeepingLimits(HousekeepingLimits.DEFAULT.withMaxPlanSteps(2)));

        assertThat(run.error()).isNull();
        assertThat(run.answer()).isEqualTo("Done.");
        assertThat(run.messages(MessageType.WARNING)).containsExactly("Couldn't make a plan (the plan had 3 steps, "
                + "more than the 2 allowed); working toward the goal step by step instead.");
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void aPlanCheckThatFailsCountsAsNoPlanNeeded() {
        MockModel model = new MockModel()
                .respond(request -> Response.builder().content("{\"needsPl").build())
                .reply("Done.");

        TestRun run = TestRuns.run(model.loopBuilder().build(), LoopRequest.builder().prompt("Do a thing").agentProfile(AUTO));

        assertThat(run.error()).isNull();
        assertThat(run.answer()).isEqualTo("Done.");
        assertThat(run.messages(MessageType.WARNING)).containsExactly("Couldn't decide whether this needs a plan "
                + "(the reply had no usable structured output); going ahead without one.");
        assertThat(run.result().execution().steps()).extracting(StepRecord::action)
                .containsExactly(StepAction.PLAN_CHECK, StepAction.COMPLETE);
        assertThat(model.requests()).hasSize(2); // no plan generation call
    }

    @Test
    void aPlanCheckCallThatThrowsCountsAsNoPlanNeeded() {
        MockModel model = new MockModel()
                .respond(request -> {
                    throw new IllegalStateException("schema refused");
                })
                .reply("Done.");

        TestRun run = TestRuns.run(model.loopBuilder().build(), LoopRequest.builder().prompt("Do a thing").agentProfile(AUTO));

        assertThat(run.error()).isNull();
        assertThat(run.answer()).isEqualTo("Done.");
        assertThat(run.messages(MessageType.WARNING)).singleElement().asString()
                .startsWith("Couldn't decide whether this needs a plan (").contains("schema refused");
    }

    @Test
    void housekeepingRequestsCarryTheirOutputCapsAndWorkingStepsDont() {
        MockModel model = new MockModel().planCheck(true).plan("Only step").reply("Done.")
                .respond(request -> Response.builder().content("{\"answer\":\"Done.\"}").build());

        TestRun run = TestRuns.run(model.loopBuilder().build(), LoopRequest.builder().prompt("Do a thing").agentProfile(AUTO)
                .answerSchema(Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")))));

        assertThat(run.error()).isNull();
        assertThat(model.requests()).extracting(Request::getMaxOutputTokens).containsExactly(2_000, 8_000, null, 16_000);
    }

    @Test
    void theOutputCapsCanBeConfigured() {
        MockModel model = new MockModel().planCheck(true).plan("Only step").reply("Done.");

        TestRun run = TestRuns.run(model.loopBuilder().build(), LoopRequest.builder().prompt("Do a thing").agentProfile(AUTO)
                .housekeepingLimits(HousekeepingLimits.DEFAULT.withPlanCheckTokens(300).withPlanTokens(3_000).withMaxPlanSteps(5)));

        assertThat(run.error()).isNull();
        assertThat(model.requests().get(0).getMaxOutputTokens()).isEqualTo(300);
        Request planning = model.requests().get(1);
        assertThat(planning.getMaxOutputTokens()).isEqualTo(3_000);
        assertThat(planning.getPrompt()).contains("(at most 5)");
        assertThat(steps(planning.getResponseSchema())).containsEntry("maxItems", 5);
    }

    @Test
    void thePlanSchemaBoundsTheStepsAndTheirText() {
        Map<String, Object> steps = steps(AgentLoopSchemas.PLAN_SCHEMA);
        assertThat(steps).containsEntry("maxItems", HousekeepingLimits.DEFAULT.maxPlanSteps()).containsEntry("minItems", 1);
        assertThat(HousekeepingLimits.DEFAULT.maxPlanSteps()).isEqualTo(12);
        assertThat(property(property(steps, "items"), "properties", "description"))
                .containsEntry("maxLength", AgentLoopSchemas.MAX_STEP_DESCRIPTION_CHARS);
        assertThat(property(AgentLoopSchemas.PLAN_SCHEMA, "properties", "summary"))
                .containsEntry("maxLength", AgentLoopSchemas.MAX_SUMMARY_CHARS);
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> HousekeepingLimits.DEFAULT.withPlanTokens(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("planTokens");
        assertThatThrownBy(() -> HousekeepingLimits.DEFAULT.withMaxPlanSteps(-1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxPlanSteps");
        assertThat(LoopRequest.builder().prompt("p").onResult(r -> { }).onError(e -> { }).build().housekeepingLimits())
                .isEqualTo(HousekeepingLimits.DEFAULT);
    }

    /**
     * Through the real Anthropic adapter and SDK to a local server: the plan schema is accepted as
     * Anthropic structured output (not refused or dropped), and the call is capped.
     */
    @Test
    void anthropicGetsThePlanSchemaAndTheCap() throws IOException {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        JsonMapper json = JsonMapper.builder().build();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            JsonNode body = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            bodies.add(body);
            String text = body.has("output_config")
                    ? "{\"summary\":\"One step\",\"steps\":[{\"description\":\"Answer it\"}]}"
                    : "The answer.";
            respond(exchange, "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                    + "\"content\":[{\"type\":\"text\",\"text\":" + json.writeValueAsString(text) + "}],"
                    + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}");
        });
        server.start();
        try {
            AnthropicAdapter adapter = new AnthropicAdapter(AnthropicOkHttpClient.builder().apiKey("test")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build());
            AgentLoop loop = AgentLoop.builder().router(new LlmRouter(List.of(adapter))).build();

            AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("Question?").agentProfile(ALWAYS_PLAN)
                    .routerConfig(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, "claude-opus-5-5"))).build()));

            assertThat(result.finalResponse().getContent()).isEqualTo("The answer.");
            assertThat(result.execution().steps().getFirst().action()).isEqualTo(StepAction.PLAN_CREATED);
            JsonNode planning = bodies.getFirst();
            assertThat(planning.path("max_tokens").asInt()).isEqualTo(8_000);
            JsonNode schema = planning.path("output_config").path("format").path("schema");
            assertThat(schema.path("properties").path("steps").path("description").asString()).contains("maxItems: 12");
            assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
            assertThat(bodies.get(1).path("max_tokens").asInt()).as("working steps keep the model's maximum").isEqualTo(128_000);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, Object> steps(Map<String, Object> planSchema) {
        return property(planSchema, "properties", "steps");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String... path) {
        Map<String, Object> node = schema;
        for (String key : path) {
            node = (Map<String, Object>) node.get(key);
        }
        return node;
    }
}
