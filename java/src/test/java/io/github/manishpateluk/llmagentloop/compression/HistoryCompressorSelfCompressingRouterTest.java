package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import com.manishpateluk.llmrouter.capability.ModelEntry;
import com.manishpateluk.llmrouter.config.RouteEntry;
import com.manishpateluk.llmrouter.config.RouterConfig;
import com.manishpateluk.llmrouter.error.RouterExhaustedException;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.provider.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every request here pins an explicit {@link RouteEntry} (rather than relying on default route
 * resolution) so the test is deterministic regardless of whichever real models {@code llm-router}
 * ships seeded in {@link ModelCapabilityTable} alongside the synthetic ones registered here.
 */
class HistoryCompressorSelfCompressingRouterTest {

    private static final String TINY_MODEL = "test-tiny-context-model";
    private static final String ROOMY_MODEL = "test-roomy-context-model";

    @AfterEach
    void removeSyntheticModels() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, TINY_MODEL);
        ModelCapabilityTable.removeModel(Provider.OPENAI, ROOMY_MODEL);
    }

    @Test
    void compressesHistoryWhenItWouldExceedTheTargetModelsContextWindow() {
        registerModel(Provider.ANTHROPIC, TINY_MODEL, 50, 5);
        CapturingProviderAdapter adapter = new CapturingProviderAdapter(Provider.ANTHROPIC);
        LlmRouter router = HistoryCompressor.newSelfCompressingRouter(List.of(adapter));

        Request request = Request.builder()
                .prompt("what's next?")
                .history(bigHistory(20))
                .config(routeTo(Provider.ANTHROPIC, TINY_MODEL))
                .build();

        router.complete(request);

        assertThat(adapter.lastRequestReceived().getHistory().size()).isLessThan(request.getHistory().size());
    }

    @Test
    void leavesARequestThatAlreadyFitsUnchanged() {
        registerModel(Provider.ANTHROPIC, ROOMY_MODEL, 100_000, 4_000);
        CapturingProviderAdapter adapter = new CapturingProviderAdapter(Provider.ANTHROPIC);
        LlmRouter router = HistoryCompressor.newSelfCompressingRouter(List.of(adapter));

        Request request = Request.builder()
                .prompt("short prompt")
                .history(List.of(Message.user("hi"), Message.assistant("hello")))
                .config(routeTo(Provider.ANTHROPIC, ROOMY_MODEL))
                .build();

        router.complete(request);

        assertThat(adapter.lastRequestReceived().getHistory()).isEqualTo(request.getHistory());
    }

    @Test
    void exhaustionPropagatesAndTheRouterThrowsWhenThereIsNoOtherCandidate() {
        registerModel(Provider.ANTHROPIC, TINY_MODEL, 1, 0); // impossible budget regardless of history
        CapturingProviderAdapter adapter = new CapturingProviderAdapter(Provider.ANTHROPIC);
        LlmRouter router = HistoryCompressor.newSelfCompressingRouter(List.of(adapter));

        Request request = Request.builder()
                .prompt("a prompt that alone already exceeds a 1-token budget")
                .config(routeTo(Provider.ANTHROPIC, TINY_MODEL))
                .build();

        assertThatThrownBy(() -> router.complete(request)).isInstanceOf(RouterExhaustedException.class);
        assertThat(adapter.lastRequestReceived()).isNull();
    }

    @Test
    void fallsBackToTheNextCandidateWhenTheFirstCannotFitEvenAfterCompression() {
        registerModel(Provider.ANTHROPIC, TINY_MODEL, 1, 0);
        registerModel(Provider.OPENAI, ROOMY_MODEL, 100_000, 4_000);
        CapturingProviderAdapter tinyAdapter = new CapturingProviderAdapter(Provider.ANTHROPIC);
        CapturingProviderAdapter roomyAdapter = new CapturingProviderAdapter(Provider.OPENAI);
        LlmRouter router = HistoryCompressor.newSelfCompressingRouter(List.of(tinyAdapter, roomyAdapter));

        Request request = Request.builder()
                .prompt("a prompt that alone already exceeds a 1-token budget")
                .config(RouterConfig.builder()
                        .route(List.of(
                                RouteEntry.of(Provider.ANTHROPIC, TINY_MODEL),
                                RouteEntry.of(Provider.OPENAI, ROOMY_MODEL)))
                        .build())
                .build();

        Response response = router.complete(request);

        assertThat(response.getContent()).isEqualTo("ok");
        assertThat(tinyAdapter.lastRequestReceived()).isNull();
        assertThat(roomyAdapter.lastRequestReceived()).isNotNull();
    }

    @Test
    void withListenerReportsCompressionAndExhaustionFromInsideTheRoutersInterceptor() {
        registerModel(Provider.ANTHROPIC, TINY_MODEL, 50, 5);
        LlmRouter router = HistoryCompressor.newSelfCompressingRouter(List.of(new CapturingProviderAdapter(Provider.ANTHROPIC)));
        List<CompressionOutcome> compressed = new ArrayList<>();
        List<CompressionExhaustedException> exhausted = new ArrayList<>();
        CompressionListener listener = new CompressionListener() {
            @Override
            public void compressed(Provider provider, String model, CompressionOutcome outcome) {
                compressed.add(outcome);
            }

            @Override
            public void exhausted(Provider provider, String model, CompressionExhaustedException failure) {
                exhausted.add(failure);
            }
        };

        HistoryCompressor.withListener(listener, () -> router.complete(Request.builder()
                .prompt("what's next?")
                .history(bigHistory(20))
                .config(routeTo(Provider.ANTHROPIC, TINY_MODEL))
                .build()));

        assertThat(compressed).singleElement().satisfies(outcome -> assertThat(outcome.compressionApplied()).isTrue());
        assertThat(exhausted).isEmpty();

        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, TINY_MODEL);
        registerModel(Provider.ANTHROPIC, TINY_MODEL, 1, 0);
        assertThatThrownBy(() -> HistoryCompressor.withListener(listener, () -> router.complete(Request.builder()
                .prompt("a prompt that alone already exceeds a 1-token budget")
                .config(routeTo(Provider.ANTHROPIC, TINY_MODEL))
                .build()))).isInstanceOf(RouterExhaustedException.class);
        assertThat(exhausted).hasSize(1);
    }

    private static RouterConfig routeTo(Provider provider, String model) {
        return RouterConfig.builder().route(List.of(RouteEntry.of(provider, model))).build();
    }

    private static List<Message> bigHistory(int turns) {
        List<Message> history = new ArrayList<>(turns);
        for (int i = 0; i < turns; i++) {
            history.add(Message.user("turn " + i + " with a decent amount of padding text to burn tokens here"));
            history.add(Message.assistant("reply " + i + " with a decent amount of padding text to burn tokens here"));
        }
        return history;
    }

    private static void registerModel(Provider provider, String model, int contextWindowTokens, int maxOutputTokens) {
        ModelCapabilityTable.registerModel(ModelEntry.builder()
                .provider(provider)
                .model(model)
                .contextWindowTokens(contextWindowTokens)
                .maxOutputTokens(maxOutputTokens)
                .build());
    }
}
