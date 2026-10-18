package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.usage.UsagePurpose;
import io.github.manishpateluk.llmagentloop.usage.UsageRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredAnswerTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "city", Map.of("type", "string"),
                    "population_millions", Map.of("type", "number")),
            "required", List.of("city"));

    private final ScriptedModel model = new ScriptedModel();

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void theFinishedAnswerIsAlsoReturnedAsDataMatchingTheSchema() {
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        AgentLoop loop = model.loop().usageMeter(records::add).build();
        model.respond(request -> request.getResponseSchema() != null || request.getPrompt().startsWith("Express the answer")
                ? Response.builder().content("{\"city\":\"Paris\",\"population_millions\":2.1}").build()
                : text("The capital is Paris, with about 2.1 million people."));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("Capital of France?")
                .agentProfile(RECURSIVE).answerSchema(SCHEMA));

        assertThat(result.finalResponse().getContent()).isEqualTo("The capital is Paris, with about 2.1 million people.");
        assertThat(result.structuredAnswer()).containsEntry("city", "Paris").containsEntry("population_millions", 2.1);
        Request formatting = model.requests.getLast();
        assertThat(formatting.getPrompt()).contains("The capital is Paris");
        assertThat(records).extracting(UsageRecord::purpose).containsExactly(UsagePurpose.STEP, UsagePurpose.ANSWER_FORMATTING);
        assertThat(result.usage().calls()).isEqualTo(2);
    }

    @Test
    void withoutASchemaThereIsNoExtraCall() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> text("Paris."));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("Capital?").agentProfile(RECURSIVE));

        assertThat(result.structuredAnswer()).isNull();
        assertThat(model.requests).hasSize(1);
    }

    @Test
    void theFormattingCallDoesNotCountTowardMaxSteps() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> request.getPrompt().startsWith("Express the answer")
                ? Response.builder().content("{\"city\":\"Paris\"}").build()
                : text("Paris."));

        AgentLoopResult result = loop.runAndWait(LoopRequest.builder().prompt("Capital?").answerSchema(SCHEMA)
                .agentProfile(AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).maxSteps(1).build()));

        assertThat(result.structuredAnswer()).containsEntry("city", "Paris");
    }

    @Test
    void aFailedConversionWarnsAndLeavesTheTextAnswerIntact() throws InterruptedException {
        AgentLoop loop = model.loop().build();
        model.respond(request -> request.getPrompt().startsWith("Express the answer") ? text("not json at all") : text("Paris."));

        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop,
                LoopRequest.builder().prompt("Capital?").agentProfile(RECURSIVE).answerSchema(SCHEMA));

        assertThat(capture.error()).isNull();
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("Paris.");
        assertThat(capture.result().structuredAnswer()).isNull();
        assertThat(capture.messages()).anyMatch(m -> m.type() == MessageType.WARNING && m.message().contains("structure"));
    }

    /** A conversion cut off at its output limit: what the model sends back when it runs out of room. */
    private static Response cutOff() {
        return Response.builder().content("{\"city\":\"Par")
                .usage(Usage.builder().outputTokens(HousekeepingLimits.DEFAULT.answerFormattingTokens()).build())
                .build();
    }

    private static boolean formatting(Request request) {
        return request.getPrompt() != null && request.getPrompt().startsWith("Express the answer");
    }

    @Test
    void aCutOffConversionIsTriedAgainAtLowThinkingOnTheSameAnswer() throws InterruptedException {
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        AgentLoop loop = model.loop().usageMeter(records::add).build();
        List<Request> conversions = new CopyOnWriteArrayList<>();
        model.respond(request -> {
            if (!formatting(request)) {
                return text("The capital is Paris.");
            }
            conversions.add(request);
            return conversions.size() == 1 ? cutOff() : Response.builder().content("{\"city\":\"Paris\"}").build();
        });

        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop, LoopRequest.builder().prompt("Capital?")
                .agentProfile(RECURSIVE).answerSchema(SCHEMA)
                .routerConfig(RouterConfig.builder().thinkingLevel(ThinkingLevel.HIGH).build()));

        assertThat(capture.result().structuredAnswer()).containsEntry("city", "Paris");
        assertThat(conversions).hasSize(2);
        assertThat(conversions).allSatisfy(r -> assertThat(r.getPrompt()).contains("The capital is Paris."));
        // The answer was written once; only the conversion ran again.
        assertThat(model.requests.stream().filter(r -> !formatting(r))).hasSize(1);
        assertThat(records).extracting(UsageRecord::purpose).containsExactly(UsagePurpose.STEP,
                UsagePurpose.ANSWER_FORMATTING, UsagePurpose.ANSWER_FORMATTING);
        assertThat(capture.messages()).anyMatch(m -> m.type() == MessageType.WARNING
                && m.message().contains("cut off") && m.message().contains("trying low thinking"));
    }

    @Test
    void theStepsGoFromTheRunsLevelToLowThinkingToACheaperModel() {
        RouterConfig run = RouterConfig.builder().thinkingLevel(ThinkingLevel.HIGH)
                .route(List.of(RouteEntry.of(Provider.ANTHROPIC, ScriptedModel.MODEL))).build();

        assertThat(AnswerFormatting.configFor(AnswerFormatting.Step.AS_RUN, run)).isSameAs(run);
        RouterConfig low = AnswerFormatting.configFor(AnswerFormatting.Step.LOW, run);
        assertThat(low.getThinkingLevel()).isEqualTo(ThinkingLevel.LOW);
        assertThat(low.isSendThinkingLevel()).isTrue();
        assertThat(low.getRoute()).isEqualTo(run.getRoute());
        RouterConfig cheaper = AnswerFormatting.configFor(AnswerFormatting.Step.CHEAPER_MODEL, run);
        assertThat(cheaper.getThinkingLevel()).isEqualTo(ThinkingLevel.LOW);
        assertThat(cheaper.isCostOptimized()).isTrue();
        assertThat(cheaper.getRoute()).containsExactly(RouteEntry.of(Provider.ANTHROPIC));
        assertThat(AnswerFormatting.configFor(AnswerFormatting.Step.CHEAPER_MODEL, null).getRoute()).isNull();
    }

    @Test
    void whenEveryStepIsCutOffTheTextAnswerStandsAndEachStepWarns() throws InterruptedException {
        AgentLoop loop = model.loop().build();
        model.respond(request -> formatting(request) ? cutOff() : text("Paris."));

        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop,
                LoopRequest.builder().prompt("Capital?").agentProfile(RECURSIVE).answerSchema(SCHEMA));

        assertThat(capture.error()).isNull();
        assertThat(capture.result().finalResponse().getContent()).isEqualTo("Paris.");
        assertThat(capture.result().structuredAnswer()).isNull();
        assertThat(model.requests.stream().filter(StructuredAnswerTest::formatting)).hasSize(3);
        assertThat(capture.messages()).filteredOn(m -> m.type() == MessageType.WARNING)
                .extracting(AgentMessage::message)
                .anyMatch(m -> m.contains("trying low thinking"))
                .anyMatch(m -> m.contains("trying a cheaper model"))
                .anyMatch(m -> m.contains("with a cheaper model") && m.contains("structuredAnswer is null"));
    }

    @Test
    void lowFirstSkipsTheRunsOwnLevel() throws InterruptedException {
        AgentLoop loop = model.loop().build();
        model.respond(request -> formatting(request) ? cutOff() : text("Paris."));

        AgentLoopRunSupport.run(loop, LoopRequest.builder().prompt("Capital?").agentProfile(RECURSIVE)
                .answerSchema(SCHEMA).answerFormatting(AnswerFormatting.LOW_FIRST));

        assertThat(model.requests.stream().filter(StructuredAnswerTest::formatting)).hasSize(2);
    }

    @Test
    void aSavedAnswerIsConvertedWithoutRunningAgain() {
        List<UsageRecord> records = new CopyOnWriteArrayList<>();
        AgentLoop loop = model.loop().usageMeter(records::add).build();
        List<Request> conversions = new CopyOnWriteArrayList<>();
        model.respond(request -> {
            conversions.add(request);
            return conversions.size() == 1 ? cutOff() : Response.builder().content("{\"city\":\"Paris\"}").build();
        });

        AgentLoop.FormattedAnswer formatted = loop.formatAnswer("The capital is Paris.", SCHEMA, null,
                AnswerFormatting.STEP_DOWN, null, Scope.forUser("u1", "s1"));

        assertThat(formatted.data()).containsEntry("city", "Paris");
        assertThat(model.requests).hasSize(2).allMatch(StructuredAnswerTest::formatting);
        assertThat(formatted.usage().calls()).isEqualTo(2);
        assertThat(records).extracting(UsageRecord::purpose).containsOnly(UsagePurpose.ANSWER_FORMATTING);
        assertThat(records).extracting(UsageRecord::scope).containsOnly(Scope.forUser("u1", "s1"));
    }

    @Test
    void theSchemaMustBeAnObject() {
        assertThatThrownBy(() -> LoopRequest.builder().prompt("x").onResult(r -> { }).onError(e -> { })
                .answerSchema(Map.of("type", "array")).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
