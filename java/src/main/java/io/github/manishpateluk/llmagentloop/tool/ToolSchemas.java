package io.github.manishpateluk.llmagentloop.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds tool parameter schemas within the JSON-schema subset every provider accepts: a flat
 * top-level {@code object}, simple property types, an explicit {@code required} list — no
 * {@code $ref}/{@code oneOf}.
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    /** {@code properties} as alternating name/schema pairs, kept in the given order. */
    public static Map<String, Object> object(List<String> required, Object... properties) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i < properties.length; i += 2) {
            props.put((String) properties[i], properties[i + 1]);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.copyOf(required));
        return schema;
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> stringArray(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    public static Map<String, Object> stringEnum(String description, List<String> values) {
        return Map.of("type", "string", "enum", List.copyOf(values), "description", description);
    }

    /** An array whose items are {@code itemSchema} (typically an {@link #object}). */
    public static Map<String, Object> array(String description, Map<String, Object> itemSchema) {
        return Map.of("type", "array", "items", itemSchema, "description", description);
    }
}
