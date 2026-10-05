package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Role;
import io.github.manishpateluk.llmrouter.model.ToolCall;
import io.github.manishpateluk.llmrouter.provider.Provider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Test helper: a mocked model. Registers a synthetic capable model, records every request the
 * router sends, and answers with a caller-supplied function. Call {@link #close()} after the test.
 */
final class ScriptedModel implements AutoCloseable {

    static final String MODEL = "test-scripted-model";
    static final AgentProfile RECURSIVE = AgentProfile.builder().planMode(PlanMode.RECURSIVE_ON_EACH_STEP).build();

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Response> responder = request -> complete("done");

    ScriptedModel() {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(Provider.ANTHROPIC).model(MODEL)
                .contextWindowTokens(200_000).maxOutputTokens(4_000)
                .supportsTools(true).supportsStructuredOutput(true).supportsVision(true).supportsFileInput(true)
                .build());
    }

    ScriptedModel respond(Function<Request, Response> responder) {
        this.responder = responder;
        return this;
    }

    LlmRouter router() {
        return new LlmRouter(List.of(new FakeProviderAdapter(request -> {
            requests.add(request);
            return responder.apply(request);
        })));
    }

    AgentLoop.Builder loop() {
        return AgentLoop.builder().router(router());
    }

    @Override
    public void close() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, MODEL);
    }

    static Response toolCall(String id, String name, Map<String, Object> arguments) {
        return Response.builder()
                .content("")
                .toolCalls(List.of(ToolCall.builder().id(id).name(name).arguments(arguments).build()))
                .build();
    }

    static Response complete(String answer) {
        return toolCall("done", "report_complete", Map.of("finalAnswer", answer));
    }

    /** The most recent tool result in a request's history, or {@code null}. */
    static String lastToolResult(Request request) {
        List<Message> history = request.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getRole() == Role.TOOL) {
                return history.get(i).getContent();
            }
        }
        return null;
    }
}
