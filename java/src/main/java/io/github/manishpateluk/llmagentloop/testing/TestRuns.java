package io.github.manishpateluk.llmagentloop.testing;

import io.github.manishpateluk.llmagentloop.AgentLoop;
import io.github.manishpateluk.llmagentloop.AgentLoopResult;
import io.github.manishpateluk.llmagentloop.AgentMessage;
import io.github.manishpateluk.llmagentloop.AnswerStream;
import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.MessageType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs an agent for a test and captures everything it reported: the result or error, every status
 * message, and the streamed answer. Uses the real asynchronous {@code run}, so callbacks behave
 * exactly as in production.
 */
public final class TestRuns {

    private TestRuns() {
    }

    /**
     * What a test run produced.
     *
     * @param result   the result, or {@code null} if the run failed
     * @param error    the failure, or {@code null} if it succeeded
     * @param messages every status message, in order
     * @param streamed the answer stream's events: text pieces, with {@code "<discard>"} marking discards
     */
    public record TestRun(AgentLoopResult result, Throwable error, List<AgentMessage> messages, List<String> streamed) {

        /** The final answer's text; fails if the run didn't succeed. */
        public String answer() {
            if (result == null) {
                throw new IllegalStateException("The run failed: " + error, error);
            }
            return result.finalResponse().getContent();
        }

        /** Messages of one type, e.g. every {@code WARNING}. */
        public List<String> messages(MessageType type) {
            return messages.stream().filter(m -> m.type() == type).map(AgentMessage::message).toList();
        }

        /** What a UI showing the stream would display at the end: the text since the last discard. */
        public String shownAnswer() {
            int last = streamed.lastIndexOf("<discard>");
            return String.join("", streamed.subList(last + 1, streamed.size()));
        }
    }

    /** Runs with a 30-second timeout. Any {@code onResult}/{@code onError}/{@code onMessage}/{@code answerStream} on the builder are replaced. */
    public static TestRun run(AgentLoop loop, LoopRequest.LoopRequestBuilder request) {
        return run(loop, request, Duration.ofSeconds(30));
    }

    public static TestRun run(AgentLoop loop, LoopRequest.LoopRequestBuilder request, Duration timeout) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AgentLoopResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<AgentMessage> messages = new CopyOnWriteArrayList<>();
        List<String> streamed = new CopyOnWriteArrayList<>();
        loop.run(request
                .onResult(r -> {
                    result.set(r);
                    done.countDown();
                })
                .onError(e -> {
                    error.set(e);
                    done.countDown();
                })
                .onMessage(messages::add)
                .answerStream(new AnswerStream() {
                    @Override
                    public void onText(String delta) {
                        streamed.add(delta);
                    }

                    @Override
                    public void onDiscard() {
                        streamed.add("<discard>");
                    }
                })
                .build());
        try {
            if (!done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("The run didn't finish within " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the run", e);
        }
        return new TestRun(result.get(), error.get(), List.copyOf(messages), List.copyOf(streamed));
    }
}
