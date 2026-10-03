package io.github.manishpateluk.llmagentloop.tool.builtin;

import com.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.memory.MemoryEntry;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in long-term memory tools — {@code memory_save}, {@code memory_search},
 * {@code memory_forget} — over the run's {@code MemoryStore}, scoped to the current user (or
 * whatever level the loop is configured with). Register with
 * {@code registry.registerAll(MemoryTools.all())}; they need {@code AgentLoop.builder().memory(...)}
 * set, e.g. to an {@code InMemoryMemoryStore}.
 */
public final class MemoryTools {

    public static final String SAVE = "memory_save";
    public static final String SEARCH = "memory_search";
    public static final String FORGET = "memory_forget";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MemoryTools() {
    }

    public static List<RegisteredTool> all() {
        return List.of(save(), search(), forget());
    }

    public static RegisteredTool save() {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(SAVE)
                        .description("Save a fact to long-term memory so it's available in future conversations with "
                                + "this user — preferences, decisions, key details about their business. Write it as "
                                + "a self-contained statement that will make sense on its own later.")
                        .parameters(ToolSchemas.object(List.of("content"),
                                "content", ToolSchemas.string("The fact to remember, as a self-contained statement."),
                                "tags", ToolSchemas.stringArray("Optional short labels to help find it later, e.g. [\"pricing\", \"q3\"].")))
                        .build(),
                (args, context) -> {
                    String content = ToolArguments.requireString(args, "content");
                    MemoryEntry entry = context.memory().save(content, ToolArguments.optionalStringList(args, "tags"));
                    return "Saved to memory with id " + entry.id() + ".";
                });
    }

    public static RegisteredTool search() {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(SEARCH)
                        .description("Search long-term memory for facts saved in earlier conversations with this user. "
                                + "Leave the query empty to list the most recent memories.")
                        .parameters(ToolSchemas.object(List.of(),
                                "query", ToolSchemas.string("What to look for, in keywords."),
                                "limit", ToolSchemas.integer("Maximum results, 1-20. Defaults to 5.")))
                        .build(),
                (args, context) -> {
                    String query = ToolArguments.optionalString(args, "query");
                    int limit = ToolArguments.optionalInt(args, "limit", 5, 1, 20);
                    List<MemoryEntry> found = context.memory().search(query == null ? "" : query, limit);
                    if (found.isEmpty()) {
                        return "No matching memories.";
                    }
                    return JSON.writeValueAsString(found.stream().map(MemoryTools::row).toList());
                });
    }

    public static RegisteredTool forget() {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(FORGET)
                        .description("Delete a memory that is wrong or no longer true, by the id memory_search returned.")
                        .parameters(ToolSchemas.object(List.of("id"),
                                "id", ToolSchemas.string("The memory's id.")))
                        .build(),
                (args, context) -> {
                    String id = ToolArguments.requireString(args, "id");
                    if (!context.memory().delete(id)) {
                        throw new ToolInputException("No memory with id " + id);
                    }
                    return "Forgot memory " + id + ".";
                });
    }

    private static Map<String, Object> row(MemoryEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entry.id());
        row.put("content", entry.content());
        if (!entry.tags().isEmpty()) {
            row.put("tags", entry.tags());
        }
        row.put("savedAt", entry.createdAt().toString());
        return row;
    }
}
