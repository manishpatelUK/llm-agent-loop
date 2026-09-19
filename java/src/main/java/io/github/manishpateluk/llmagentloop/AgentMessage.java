package io.github.manishpateluk.llmagentloop;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A single status update delivered to a {@link LoopRequest#onMessage()} callback.
 *
 * @param type      what kind of update this is
 * @param message   human-readable status text
 * @param timestamp when the update was produced; defaults to {@link Instant#now()} if not supplied
 * @param metadata  free-form extra detail (e.g. a tool name, a step index); defaults to empty
 */
public record AgentMessage(MessageType type, String message, Instant timestamp, Map<String, Object> metadata) {

    public AgentMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        timestamp = timestamp == null ? Instant.now(Clock.systemUTC()) : timestamp;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentMessage of(MessageType type, String message) {
        return new AgentMessage(type, message, Instant.now(), Map.of());
    }

    public static AgentMessage of(MessageType type, String message, Map<String, Object> metadata) {
        return new AgentMessage(type, message, Instant.now(), metadata);
    }
}
