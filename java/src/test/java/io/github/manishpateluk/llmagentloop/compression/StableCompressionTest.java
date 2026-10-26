package io.github.manishpateluk.llmagentloop.compression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.capability.ModelCapabilityTable;
import io.github.manishpateluk.llmrouter.capability.ModelEntry;
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.model.Message;
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.Response;
import io.github.manishpateluk.llmrouter.model.Usage;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmrouter.provider.ProviderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Once a growing history needs compressing, a self-compressing router keeps the compressed start of it from call
 * to call — so the provider's prompt cache keeps matching, and an LLM summary is made once, not on every call.
 */
class StableCompressionTest {

    private static final String TINY = "test-stable-compression-model";
    private final List<Request> sent = new CopyOnWriteArrayList<>();

    @BeforeEach
    void registerTinyModel() {
        ModelCapabilityTable.registerModel(ModelEntry.builder().provider(Provider.ANTHROPIC).model(TINY)
                .contextWindowTokens(400).maxOutputTokens(0).build());
    }

    @AfterEach
    void removeTinyModel() {
        ModelCapabilityTable.removeModel(Provider.ANTHROPIC, TINY);
    }

    private final ProviderAdapter recording = new ProviderAdapter() {
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
            sent.add(request);
            return Response.builder().content("ok").usage(Usage.builder().inputTokens(1).outputTokens(1).build()).build();
        }
    };

    private static boolean isSummary(Request request) {
        return request.getPrompt().toLowerCase(Locale.ROOT).contains("summar");
    }

    @Test
    void aLoopsCallsKeepTheirCompressedStartAndSummarizeOnce() {
        List<LlmRouter> routers = List.of(
                HistoryCompressor.newSelfCompressingRouter(List.of(recording), List.of(CompressionMethod.LLM_SUMMARIZATION)),
                customMethodsRouter());
        for (LlmRouter router : routers) {
            sent.clear();
            List<Message> history = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                history.add(Message.user("turn " + i + " with a decent amount of padding text to burn tokens here"));
                history.add(Message.assistant("reply " + i + " with a decent amount of padding text to burn tokens here"));
            }
            List<Request> delivered = new ArrayList<>();
            for (int step = 0; step < 4; step++) {
                router.complete(Request.builder().prompt("Continue.").history(List.copyOf(history))
                        .config(RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, TINY))).build()).build());
                delivered.add(sent.stream().filter(r -> !isSummary(r)).reduce((a, b) -> b).orElseThrow());
                history.add(Message.assistant("step " + step));
                history.add(Message.user("result " + step));
            }

            assertThat(sent.stream().filter(StableCompressionTest::isSummary)).as("one summary, not one per call").hasSize(1);
            assertThat(delivered.getFirst().getHistory().size()).as("the first call was compressed").isLessThan(40);
            for (int i = 1; i < delivered.size(); i++) {
                List<Message> earlier = delivered.get(i - 1).getHistory();
                List<Message> later = delivered.get(i).getHistory();
                assertThat(later.subList(0, earlier.size())).as("call %d starts with call %d's compressed history", i, i - 1)
                        .isEqualTo(earlier);
                assertThat(later).hasSize(earlier.size() + 2);
            }
        }
    }

    @Test
    void anUnrelatedHistoryIsCompressedOnItsOwn() {
        LlmRouter router = HistoryCompressor.newSelfCompressingRouter(List.of(recording),
                List.of(CompressionMethod.SLIDING_WINDOW_TRUNCATION));
        RouterConfig tiny = RouterConfig.builder().route(List.of(RouteEntry.of(Provider.ANTHROPIC, TINY))).build();
        List<Message> first = new ArrayList<>();
        List<Message> second = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            first.add(Message.user("alpha " + i + " with a decent amount of padding text to burn tokens here"));
            second.add(Message.user("beta " + i + " with a decent amount of padding text to burn tokens here"));
        }

        router.complete(Request.builder().prompt("go").history(first).config(tiny).build());
        router.complete(Request.builder().prompt("go").history(second).config(tiny).build());

        assertThat(sent.getLast().getHistory()).allSatisfy(m -> assertThat(m.getContent()).startsWith("beta"));
    }

    private LlmRouter customMethodsRouter() {
        AtomicReference<LlmRouter> self = new AtomicReference<>();
        LlmRouter router = new LlmRouter(List.of(recording),
                HistoryCompressor.interceptor(self, List.of(CompressionMethod.LLM_SUMMARIZATION)));
        self.set(router);
        return router;
    }
}
