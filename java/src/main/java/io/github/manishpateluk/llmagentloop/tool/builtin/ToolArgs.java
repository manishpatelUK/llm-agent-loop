package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmagentloop.tool.ToolInputException;

import java.util.List;
import java.util.Map;

/** Reads model-supplied tool arguments, turning bad ones into {@link ToolInputException}s the model can act on. */
final class ToolArgs {

    private ToolArgs() {
    }

    static String requireString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ToolInputException("'" + name + "' is required and must be a non-empty string");
        }
        return text;
    }

    /** Like {@link #requireString} but allows an empty string — for content that may legitimately be empty. */
    static String requireStringAllowEmpty(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof String text)) {
            throw new ToolInputException("'" + name + "' is required and must be a string");
        }
        return text;
    }

    static String optionalString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ToolInputException("'" + name + "' must be a string");
        }
        return text;
    }

    static int optionalInt(Map<String, Object> args, String name, int defaultValue, int min, int max) {
        Object value = args.get(name);
        if (value == null) {
            return defaultValue;
        }
        long number;
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            number = n.longValue();
        } else if (value instanceof String text && text.strip().matches("-?\\d+")) {
            number = Long.parseLong(text.strip());
        } else {
            throw new ToolInputException("'" + name + "' must be a whole number");
        }
        if (number < min || number > max) {
            throw new ToolInputException("'" + name + "' must be between " + min + " and " + max);
        }
        return (int) number;
    }

    static boolean optionalBoolean(Map<String, Object> args, String name, boolean defaultValue) {
        Object value = args.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text);
        }
        throw new ToolInputException("'" + name + "' must be true or false");
    }

    static List<String> optionalStringList(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new ToolInputException("'" + name + "' must be a list of strings");
        }
        return list.stream().map(String.class::cast).toList();
    }
}
