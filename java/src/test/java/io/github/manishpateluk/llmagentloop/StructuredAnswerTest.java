package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
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

    @Test
    void theSchemaMustBeAnObject() {
        assertThatThrownBy(() -> LoopRequest.builder().prompt("x").onResult(r -> { }).onError(e -> { })
                .answerSchema(Map.of("type", "array")).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
