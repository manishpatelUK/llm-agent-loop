package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code {{name}}} / {@code {{step.value}}} templates of {@link Procedure}s: finding their
 * references, and filling them in for a path, query or form value, plain text, or a JSON tree.
 */
final class ProcedureTemplates {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern REF = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\.([A-Za-z_][A-Za-z0-9_]*))?\\s*}}");

    /** {@code {{name}}} (a parameter: {@code step} is null) or {@code {{step.value}}}. */
    record Ref(String step, String name) {

        @Override
        public String toString() {
            return step == null ? name : step + "." + name;
        }
    }

    /** What templates are filled from: arguments by name, and each finished step's kept values. */
    record Values(Map<String, JsonNode> arguments, Map<String, Map<String, JsonNode>> steps) {

        /** The value, or {@code null} for an optional parameter that wasn't given. */
        JsonNode get(Ref ref) {
            if (ref.step() == null) {
                return arguments.get(ref.name());
            }
            Map<String, JsonNode> kept = steps.get(ref.step());
            return kept == null ? null : kept.get(ref.name());
        }
    }

    private ProcedureTemplates() {
    }

    /** Every reference in {@code template}, refusing {@code {{...}}} that isn't a valid one. */
    static List<Ref> refs(String template) {
        List<Ref> refs = new ArrayList<>();
        if (template == null) {
            return refs;
        }
        Matcher m = REF.matcher(template);
        while (m.find()) {
            refs.add(m.group(2) == null ? new Ref(null, m.group(1)) : new Ref(m.group(1), m.group(2)));
        }
        if (REF.matcher(template).replaceAll("").contains("{{")) {
            throw new IllegalArgumentException("'" + template + "' has a placeholder that isn't {{name}} or {{step.value}}");
        }
        return refs;
    }

    /** Every reference in a JSON template's string values; keys can't hold placeholders. */
    static List<Ref> jsonRefs(JsonNode template) {
        List<Ref> refs = new ArrayList<>();
        if (template.isString()) {
            refs.addAll(refs(template.asString()));
        } else if (template.isObject()) {
            for (Map.Entry<String, JsonNode> field : template.properties()) {
                if (field.getKey().contains("{{")) {
                    throw new IllegalArgumentException("JSON keys can't hold placeholders: " + field.getKey());
                }
                refs.addAll(jsonRefs(field.getValue()));
            }
        } else if (template.isArray()) {
            template.forEach(item -> refs.addAll(jsonRefs(item)));
        }
        return refs;
    }

    static JsonNode parseJson(String template, String what) {
        try {
            return JSON.readTree(template);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(what + " must be valid JSON (with placeholders inside strings): " + e.getOriginalMessage());
        }
    }

    /** The single reference {@code template} consists of, or {@code null} if it's anything more. */
    static Ref whole(String template) {
        Matcher m = REF.matcher(template.strip());
        if (!m.matches()) {
            return null;
        }
        return m.group(2) == null ? new Ref(null, m.group(1)) : new Ref(m.group(1), m.group(2));
    }

    /** A path: each value percent-encoded as one segment, so it can't add structure ({@code /}, {@code ?}, {@code ..}). */
    static String path(String template, Values values) {
        return fill(template, values, value -> {
            if (value.equals(".") || value.equals("..")) {
                throw new ToolInputException("A path value can't be '" + value + "'");
            }
            return ApiHttp.encode(value);
        }, true);
    }

    /** A query or form value, or {@code null} to leave the entry out (just an optional parameter that wasn't given). */
    static String value(String template, Values values) {
        Ref whole = whole(template);
        if (whole != null && values.get(whole) == null) {
            return null;
        }
        return fill(template, values, UnaryOperator.identity(), false);
    }

    /** Plain text. */
    static String text(String template, Values values) {
        return fill(template, values, UnaryOperator.identity(), false);
    }

    /**
     * A JSON template filled in as JSON: a string that is just {@code "{{ref}}"} becomes the value
     * itself (a number stays a number, a kept object stays an object), and its field or array item
     * is dropped when that's an optional parameter that wasn't given; references inside longer
     * strings are filled in as text.
     */
    static JsonNode json(JsonNode template, Values values) {
        if (template.isString()) {
            Ref whole = whole(template.asString());
            if (whole != null) {
                JsonNode value = values.get(whole);
                return value == null ? null : value.deepCopy();
            }
            return JSON.getNodeFactory().stringNode(text(template.asString(), values));
        }
        if (template.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> field : template.properties()) {
                JsonNode value = json(field.getValue(), values);
                if (value != null) {
                    out.set(field.getKey(), value);
                }
            }
            return out;
        }
        if (template.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode item : template) {
                JsonNode value = json(item, values);
                if (value != null) {
                    out.add(value);
                }
            }
            return out;
        }
        return template.deepCopy();
    }

    private static String fill(String template, Values values, UnaryOperator<String> encode, boolean required) {
        Matcher m = REF.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Ref ref = m.group(2) == null ? new Ref(null, m.group(1)) : new Ref(m.group(1), m.group(2));
            JsonNode value = values.get(ref);
            if (value == null && required) {
                throw new ToolInputException("No value for {{" + ref + "}}");
            }
            String text = value == null ? "" : text(value);
            if (required && value.isContainer()) {
                throw new ToolInputException("{{" + ref + "}} is a JSON " + (value.isArray() ? "array" : "object")
                        + " and can't go in a path");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(encode.apply(text)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A value as text: strings as they are, numbers and booleans as written, null as empty, containers as JSON. */
    static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        if (value.isContainer()) {
            return JSON.writeValueAsString(value);
        }
        return value.asString();
    }
}
