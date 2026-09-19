package io.github.manishpateluk.llmagentloop;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentMessageTest {

    @Test
    void ofSetsATimestampAndEmptyMetadataByDefault() {
        AgentMessage message = AgentMessage.of(MessageType.THINKING, "working on it");

        assertThat(message.type()).isEqualTo(MessageType.THINKING);
        assertThat(message.message()).isEqualTo("working on it");
        assertThat(message.timestamp()).isNotNull();
        assertThat(message.metadata()).isEmpty();
    }

    @Test
    void ofWithMetadataCarriesItThrough() {
        AgentMessage message = AgentMessage.of(MessageType.TOOL_CALL, "calling search", Map.of("tool", "search"));

        assertThat(message.metadata()).containsEntry("tool", "search");
    }

    @Test
    void nullTimestampAndMetadataFallBackToDefaults() {
        AgentMessage message = new AgentMessage(MessageType.INFO, "note", null, null);

        assertThat(message.timestamp()).isNotNull();
        assertThat(message.metadata()).isEmpty();
    }

    @Test
    void suppliedTimestampIsPreserved() {
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        AgentMessage message = new AgentMessage(MessageType.INFO, "note", fixed, Map.of());

        assertThat(message.timestamp()).isEqualTo(fixed);
    }

    @Test
    void requiresTypeAndMessage() {
        assertThatThrownBy(() -> new AgentMessage(null, "note", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentMessage(MessageType.INFO, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
