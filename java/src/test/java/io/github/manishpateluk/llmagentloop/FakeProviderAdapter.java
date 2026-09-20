package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.Usage;
import com.manishpateluk.llmrouter.provider.Provider;
import com.manishpateluk.llmrouter.provider.ProviderAdapter;

import java.util.function.Function;

/**
 * A scriptable {@link ProviderAdapter} test double — always "available", answers via a
 * caller-supplied function. Fills in a placeholder {@code usage} when the canned response didn't
 * set one, since {@code LlmRouter}'s own core requires every adapter response to carry one.
 */
final class FakeProviderAdapter implements ProviderAdapter {

    private static final Usage PLACEHOLDER_USAGE = Usage.builder().inputTokens(1).outputTokens(1).build();

    private final Function<Request, Response> responder;

    FakeProviderAdapter(Function<Request, Response> responder) {
        this.responder = responder;
    }

    @Override
    public Provider id() {
        return Provider.ANTHROPIC;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        Response response = responder.apply(adaptedRequest);
        return response.getUsage() != null ? response : response.toBuilder().usage(PLACEHOLDER_USAGE).build();
    }
}
