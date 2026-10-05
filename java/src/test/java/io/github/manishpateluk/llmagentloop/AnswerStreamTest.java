package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.manishpateluk.llmagentloop.ScriptedModel.RECURSIVE;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.lastToolResult;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.text;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.textAndToolCall;
import static io.github.manishpateluk.llmagentloop.ScriptedModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

class AnswerStreamTest {

    private final ScriptedModel model = new ScriptedModel();
    private final Recorder stream = new Recorder();

    /** Records the stream, with discards marked. */
    private static final class Recorder implements AnswerStream {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onText(String delta) {
            events.add(delta);
        }

        @Override
        public void onDiscard() {
            events.add("<discard>");
        }

        /** What a UI would be showing: text since the last discard. */
        String shown() {
            int last = events.lastIndexOf("<discard>");
            return String.join("", events.subList(last + 1, events.size()));
        }
    }

    @AfterEach
    void tearDown() {
        model.close();
    }

    @Test
    void theFinalAnswerStreamsAndAPreambleBeforeAToolCallIsDiscarded() {
        AgentLoop loop = model.loop().tools(lookupTool()).build();
        model.respond(request -> lastToolResult(request) == null
                ? textAndToolCall("Let me check. ", "l", "lookup", Map.of())
                : text("The answer is 42."));

        AgentLoopResult result = loop.runAndWait(request());

        assertThat(stream.events).containsExactly("Let ", "me ", "check. ", "<discard>", "The ", "answer ", "is ", "42.");
        assertThat(stream.shown()).isEqualTo("The answer is 42.").isEqualTo(result.finalResponse().getContent());
    }

    @Test
    void whileStreamingReportCompleteIsNotOfferedSoTheAnswerArrivesAsText() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> text("Hi!"));

        loop.runAndWait(request());
        loop.runAndWait(LoopRequest.builder().prompt("hello").agentProfile(RECURSIVE));

        assertThat(names(model.requests.get(0))).doesNotContain("report_complete");
        assertThat(names(model.requests.get(1))).contains("report_complete");
        assertThat(model.requests.get(0).getSystemInstructions()).contains(AgentLoop.FINAL_ANSWER_GUIDANCE);
    }

    @Test
    void subTasksDoNotStream() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> {
            if (request.getPrompt().equals("research competitors")) {
                return text("Competitor A and B.");
            }
            return lastToolResult(request) == null
                    ? toolCall("s", "spawn_sub_task", Map.of("goal", "research competitors"))
                    : text("Summary: A and B lead the market.");
        });

        loop.runAndWait(request());

        assertThat(stream.shown()).isEqualTo("Summary: A and B lead the market.");
        assertThat(String.join("", stream.events)).doesNotContain("Competitor A");
    }

    @Test
    void onlyAPlansFinalStepStreams() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> {
            if (request.getPrompt().contains("Produce an ordered list of steps")) {
                return Response.builder()
                        .content("{\"summary\":\"two\",\"steps\":[{\"description\":\"gather\"},{\"description\":\"write\"}]}").build();
            }
            return request.getPrompt().endsWith("gather") ? text("Gathered facts.") : text("Final write-up.");
        });

        loop.runAndWait(LoopRequest.builder().prompt("report").answerStream(stream)
                .agentProfile(AgentProfile.builder().planMode(PlanMode.ALWAYS_PLAN).build()));

        assertThat(stream.shown()).isEqualTo("Final write-up.");
        assertThat(String.join("", stream.events)).doesNotContain("Gathered");
    }

    @Test
    void aDirectAnswerStreams() {
        AgentLoop loop = model.loop().build();
        model.respond(request -> text("Paris is the capital."));

        loop.runAndWait(LoopRequest.builder().prompt("Capital of France?").answerStream(stream)
                .agentProfile(AgentProfile.builder().planMode(PlanMode.NEVER_PLAN).build()));

        assertThat(stream.shown()).isEqualTo("Paris is the capital.");
        assertThat(model.streamedRequests).hasSize(1);
    }

    @Test
    void withoutAStreamNothingIsStreamedAndAPlainTextAnswerCompletesNormally() throws InterruptedException {
        AgentLoop loop = model.loop().build();
        model.respond(request -> text("Done, no tool needed."));

        AgentLoopRunSupport.Capture capture = AgentLoopRunSupport.run(loop,
                LoopRequest.builder().prompt("hello").agentProfile(RECURSIVE));

        assertThat(capture.result().finalResponse().getContent()).isEqualTo("Done, no tool needed.");
        assertThat(model.streamedRequests).isEmpty();
        assertThat(capture.messages()).noneMatch(m -> m.type() == MessageType.WARNING);
    }

    private LoopRequest.LoopRequestBuilder request() {
        return LoopRequest.builder().prompt("question").answerStream(stream).agentProfile(RECURSIVE);
    }

    private static List<String> names(Request request) {
        return request.getTools().stream().map(ToolDefinition::getName).toList();
    }

    private static ToolRegistry lookupTool() {
        return new ToolRegistry().register(
                ToolDefinition.builder().name("lookup").description("look up").parameters(Map.of("type", "object")).build(),
                (args, context) -> "42");
    }
}
