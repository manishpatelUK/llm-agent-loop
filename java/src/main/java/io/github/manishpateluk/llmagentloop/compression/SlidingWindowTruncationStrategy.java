package io.github.manishpateluk.llmagentloop.compression;

import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.model.Message;
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops the oldest non-{@link Role#SYSTEM} turns one at a time until the request fits under
 * {@code targetTokens}, or there is nothing left to drop without discarding the most recent
 * turn (which is always kept, since it's what the model is being asked to continue from).
 */
final class SlidingWindowTruncationStrategy implements CompressionStrategy {

    @Override
    public Request compress(Request request, int targetTokens, LlmRouter router) {
        List<Message> history = new ArrayList<>(request.getHistory());
        Request current = request;

        while (RequestTokenEstimator.estimateTokens(current) > targetTokens) {
            int removeAt = indexOfOldestRemovable(history);
            if (removeAt < 0) {
                break;
            }
            history.remove(removeAt);
            current = current.toBuilder().history(List.copyOf(history)).build();
        }

        return current;
    }

    /** Never removes the last message, and skips pinned {@link Role#SYSTEM} turns. */
    private static int indexOfOldestRemovable(List<Message> history) {
        if (history.size() <= 1) {
            return -1;
        }
        for (int i = 0; i < history.size() - 1; i++) {
            if (history.get(i).getRole() != Role.SYSTEM) {
                return i;
            }
        }
        return -1;
    }
}
