package io.github.manishpateluk.llmagentloop.tool;

import com.manishpateluk.llmrouter.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    private final ToolDefinition echoDefinition = ToolDefinition.builder()
            .name("echo")
            .description("Echoes text back")
            .parameters(Map.of("type", "object"))
            .build();

    @Test
    void findReturnsEmptyForAnUnregisteredTool() {
        ToolRegistry registry = new ToolRegistry();

        assertThat(registry.find("echo")).isEmpty();
    }

    @Test
    void registeredToolIsFindableByName() {
        ToolRegistry registry = new ToolRegistry();
        ToolHandler handler = args -> "echo:" + args.get("text");

        registry.register(echoDefinition, handler);

        assertThat(registry.find("echo")).isPresent();
        assertThat(registry.find("echo").get().handler()).isSameAs(handler);
    }

    @Test
    void definitionsListsEveryRegisteredTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoDefinition, args -> "echo");

        assertThat(registry.definitions()).extracting(ToolDefinition::getName).containsExactly("echo");
    }

    @Test
    void registeringTheSameNameAgainReplacesTheHandler() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(echoDefinition, args -> "first");
        registry.register(echoDefinition, args -> "second");

        assertThat(registry.find("echo").get().handler().handle(Map.of())).isEqualTo("second");
        assertThat(registry.definitions()).hasSize(1);
    }
}
