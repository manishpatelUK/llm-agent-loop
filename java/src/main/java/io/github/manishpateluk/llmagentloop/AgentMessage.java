package io.github.manishpateluk.llmagentloop;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A single status update delivered to a {@link LoopRequest#onMessage()} callback.
 *
 * @param executionId the {@link AgentLoop} run this update belongs to
 * @param thread      which branch of that run produced it (0 is the run's main thread)
 * @param type        what kind of update this is
 * @param message     human-readable status text — where it's cheap to do (already part of a
 *                    structured-output call being made anyway), this is authored by the LLM
 *                    itself rather than a static template
 * @param timestamp   when the update was produced; defaults to {@link Instant#now()} if not supplied
 * @param metadata    free-form extra detail (e.g. a tool name); defaults to empty
 */
public record AgentMessage(
        UUID executionId,
        int thread,
        MessageType type,
        String message,
        Instant timestamp,
        Map<String, Object> metadata) {

    public AgentMessage {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        timestamp = timestamp == null ? Instant.now() : timestamp;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentMessage of(UUID executionId, int thread, MessageType type, String message) {
        return new AgentMessage(executionId, thread, type, message, Instant.now(), Map.of());
    }

    public static AgentMessage of(
            UUID executionId, int thread, MessageType type, String message, Map<String, Object> metadata) {
        return new AgentMessage(executionId, thread, type, message, Instant.now(), metadata);
    }
}
