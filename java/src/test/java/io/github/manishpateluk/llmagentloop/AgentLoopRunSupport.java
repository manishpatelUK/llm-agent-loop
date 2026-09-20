package io.github.manishpateluk.llmagentloop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared test helper: runs a {@link LoopRequest.LoopRequestBuilder} to completion and captures its callbacks. */
final class AgentLoopRunSupport {

    private AgentLoopRunSupport() {
    }

    static Capture run(AgentLoop loop, LoopRequest.LoopRequestBuilder builder) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AgentLoopResult> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        List<AgentMessage> messages = new CopyOnWriteArrayList<>();

        loop.run(builder
                .onResult(r -> {
                    resultRef.set(r);
                    latch.countDown();
                })
                .onError(e -> {
                    errorRef.set(e);
                    latch.countDown();
                })
                .onMessage(messages::add)
                .build());

        assertThat(latch.await(5, TimeUnit.SECONDS)).as("run completed within timeout").isTrue();
        return new Capture(resultRef.get(), errorRef.get(), messages);
    }

    record Capture(AgentLoopResult result, Throwable error, List<AgentMessage> messages) {
    }
}
