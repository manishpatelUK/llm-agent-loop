package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Response;
import com.manishpateluk.llmrouter.model.Usage;
import com.manishpateluk.llmrouter.provider.Provider;
import com.manishpateluk.llmrouter.provider.ProviderAdapter;

/** A {@link ProviderAdapter} test double that records the (possibly-intercepted) request it actually receives. */
final class CapturingProviderAdapter implements ProviderAdapter {

    private final Provider provider;
    private volatile Request lastRequestReceived;

    CapturingProviderAdapter(Provider provider) {
        this.provider = provider;
    }

    @Override
    public Provider id() {
        return provider;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Response send(String model, Request adaptedRequest) {
        this.lastRequestReceived = adaptedRequest;
        return Response.builder()
                .content("ok")
                .usage(Usage.builder().inputTokens(1).outputTokens(1).build())
                .build();
    }

    Request lastRequestReceived() {
        return lastRequestReceived;
    }
}
