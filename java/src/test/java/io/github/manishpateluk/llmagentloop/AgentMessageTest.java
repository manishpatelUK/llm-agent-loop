package io.github.manishpateluk.llmagentloop;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentMessageTest {

    private final UUID executionId = UUID.randomUUID();

    @Test
    void ofSetsATimestampAndEmptyMetadataByDefault() {
        AgentMessage message = AgentMessage.of(executionId, 0, MessageType.THINKING, "working on it");

        assertThat(message.executionId()).isEqualTo(executionId);
        assertThat(message.thread()).isZero();
        assertThat(message.type()).isEqualTo(MessageType.THINKING);
        assertThat(message.message()).isEqualTo("working on it");
        assertThat(message.timestamp()).isNotNull();
        assertThat(message.metadata()).isEmpty();
    }

    @Test
    void ofWithMetadataCarriesItThrough() {
        AgentMessage message = AgentMessage.of(executionId, 1, MessageType.TOOL_CALL, "calling search", Map.of("tool", "search"));

        assertThat(message.thread()).isEqualTo(1);
        assertThat(message.metadata()).containsEntry("tool", "search");
    }

    @Test
    void nullTimestampAndMetadataFallBackToDefaults() {
        AgentMessage message = new AgentMessage(executionId, 0, MessageType.INFO, "note", null, null);

        assertThat(message.timestamp()).isNotNull();
        assertThat(message.metadata()).isEmpty();
    }

    @Test
    void suppliedTimestampIsPreserved() {
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        AgentMessage message = new AgentMessage(executionId, 0, MessageType.INFO, "note", fixed, Map.of());

        assertThat(message.timestamp()).isEqualTo(fixed);
    }

    @Test
    void requiresExecutionIdTypeAndMessage() {
        assertThatThrownBy(() -> new AgentMessage(null, 0, MessageType.INFO, "note", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentMessage(executionId, 0, null, "note", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentMessage(executionId, 0, MessageType.INFO, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
