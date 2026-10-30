package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A saved recipe for a task against one {@link ApiConnection}: named, versioned, with typed
 * parameters and a fixed list of {@link ProcedureStep}s that run in code, one after another, with
 * no model call between them. An agent works out how to do something against an unfamiliar API,
 * then saves it as a procedure (with {@code procedure_save}) that it — or your code, through a
 * {@link ProcedureRunner} — can run again with new arguments.
 *
 * <pre>{@code
 * Procedure addNote = Procedure.builder("add_contact_note", "crm")
 *         .description("Find a contact by email and add a note to them")
 *         .parameter(Procedure.Parameter.required("email", "string", "The contact's email"))
 *         .parameter(Procedure.Parameter.required("text", "string", "The note"))
 *         .step(ProcedureStep.builder("find", "GET", "/contacts").query("email", "{{email}}").keep("id", "/data/0/id").build())
 *         .step(ProcedureStep.builder("note", "POST", "/contacts/{{find.id}}/notes").body("{\"body\": \"{{text}}\"}").keep("id", "/id").build())
 *         .output("{\"contact\": \"{{find.id}}\", \"note\": \"{{note.id}}\"}")
 *         .build();
 * }</pre>
 *
 * <p>Every template reference is checked when a procedure is made: a parameter it declares, or a
 * value an <em>earlier</em> step keeps. Whether the connection allows its methods and paths is
 * checked when it's saved through {@code procedure_save} and again every time it runs.
 *
 * @param name        unique within a scope's procedures ({@code ^[a-zA-Z0-9_-]{1,64}$})
 * @param version     1 for the first saved version, then 2, 3...; 0 for one not saved yet ({@link ProcedureStore} assigns it)
 * @param connection  the name of the connection it runs against
 * @param description what it does and when to use it, for the model
 * @param inputSchema its parameters as a JSON schema: an object of {@code string}, {@code integer},
 *                    {@code number} and {@code boolean} properties, each with an optional
 *                    {@code description}, {@code enum} (strings) and {@code default}, and a
 *                    {@code required} list. Kept in that canonical form; see {@link #parameters()}
 * @param steps       1 to {@value #MAX_STEPS} calls, run in order
 * @param output      a JSON template for the result, e.g. {@code {"id": "{{create.id}}"}}; {@code null} to return the last response's body
 * @param savedAt     when this version was saved; {@code null} if it hasn't been
 */
public record Procedure(String name, int version, String connection, String description, Map<String, Object> inputSchema,
                        List<ProcedureStep> steps, String output, Instant savedAt) {

    public static final int MAX_STEPS = 20;
    static final List<String> TYPES = List.of("string", "integer", "number", "boolean");
    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /**
     * One parameter.
     *
     * @param type          {@code string}, {@code integer}, {@code number} or {@code boolean}
     * @param allowedValues for strings, the only values accepted (a JSON-schema {@code enum}); empty for any
     * @param defaultValue  used when the argument isn't given; {@code null} for none
     */
    public record Parameter(String name, String type, String description, boolean required, List<String> allowedValues,
                            Object defaultValue) {

        public Parameter {
            requireIdentifier(name, "Parameter name");
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException("Parameter '" + name + "' must have type " + String.join(", ", TYPES));
            }
            description = description == null ? "" : description;
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
            if (!allowedValues.isEmpty() && !type.equals("string")) {
                throw new IllegalArgumentException("Parameter '" + name + "': allowed values are only for strings");
            }
            if (defaultValue != null) {
                JsonNode coerced = coerce(name, type, allowedValues, defaultValue, IllegalArgumentException::new);
                defaultValue = ProcedureTemplates.JSON.convertValue(coerced, Object.class);
            }
        }

        public static Parameter required(String name, String type, String description) {
            return new Parameter(name, type, description, true, List.of(), null);
        }

        public static Parameter optional(String name, String type, String description) {
            return new Parameter(name, type, description, false, List.of(), null);
        }

        /** This parameter as one property of a JSON schema. */
        Map<String, Object> schema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", type);
            if (!description.isBlank()) {
                schema.put("description", description);
            }
            if (!allowedValues.isEmpty()) {
                schema.put("enum", allowedValues);
            }
            if (defaultValue != null) {
                schema.put("default", defaultValue);
            }
            return Collections.unmodifiableMap(schema);
        }

        /** E.g. {@code email* (string)} or {@code limit (integer, default 10)}. */
        String summary() {
            StringBuilder out = new StringBuilder(name).append(required ? "*" : "").append(" (").append(type);
            if (!allowedValues.isEmpty()) {
                out.append(": ").append(String.join(" | ", allowedValues));
            }
            if (defaultValue != null) {
                out.append(", default ").append(defaultValue);
            }
            out.append(')');
            if (!description.isBlank()) {
                out.append(" ").append(description);
            }
            return out.toString();
        }
    }

    public Procedure {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Procedure name must match " + NAME + ": " + name);
        }
        if (connection == null || connection.isBlank()) {
            throw new IllegalArgumentException("Procedure '" + name + "' needs a connection");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be 0 or more");
        }
        description = description == null ? "" : description.strip();
        if (steps == null || steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Procedure '" + name + "' needs between 1 and " + MAX_STEPS + " steps");
        }
        steps = List.copyOf(steps);
        List<Parameter> parameters = parseSchema(inputSchema);
        inputSchema = schema(parameters);
        output = output == null || output.isBlank() ? null : output.strip();
        checkReferences(parameters, steps, output);
    }

    /** {@code inputSchema} built from parameters, for making procedures in code. */
    public static Map<String, Object> schema(List<Parameter> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : parameters) {
            if (properties.put(parameter.name(), parameter.schema()) != null) {
                throw new IllegalArgumentException("Duplicate parameter: " + parameter.name());
            }
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.copyOf(required));
        return Collections.unmodifiableMap(schema);
    }

    /** The parameters {@link #inputSchema()} declares, in order. */
    public List<Parameter> parameters() {
        return parseSchema(inputSchema);
    }

    /** Whether any step changes something (uses a method other than GET or HEAD). */
    public boolean writes() {
        return steps.stream().anyMatch(ProcedureStep::writes);
    }

    /** This procedure as saved: the version and time a {@link ProcedureStore} gives it. */
    public Procedure withVersion(int version, Instant savedAt) {
        return new Procedure(name, version, connection, description, inputSchema, steps, output, savedAt);
    }

    /** As JSON, for storing as one document or column; read back with {@link #fromJson}. */
    public String toJson() {
        return ProcedureTemplates.JSON.writeValueAsString(this);
    }

    public static Procedure fromJson(String json) {
        try {
            return ProcedureTemplates.JSON.readValue(json, Procedure.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not a valid procedure: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * {@code arguments} checked against the parameters and turned into JSON values: unknown names,
     * missing required ones and values of the wrong type are refused; defaults are filled in;
     * {@code "42"} is accepted for an integer, {@code "true"} for a boolean.
     *
     * @throws ToolInputException naming the parameter, so the model can correct the call
     */
    Map<String, JsonNode> checkArguments(Map<String, ?> arguments) {
        Map<String, ?> given = arguments == null ? Map.of() : arguments;
        List<Parameter> parameters = parameters();
        Set<String> names = new HashSet<>();
        parameters.forEach(p -> names.add(p.name()));
        for (String key : given.keySet()) {
            if (!names.contains(key)) {
                throw new ToolInputException("Unknown argument '" + key + "'; " + name + " takes: "
                        + (names.isEmpty() ? "no arguments" : String.join(", ", parameters.stream().map(Parameter::name).toList())));
            }
        }
        Map<String, JsonNode> checked = new LinkedHashMap<>();
        for (Parameter parameter : parameters) {
            Object value = given.get(parameter.name());
            if (value == null) {
                value = parameter.defaultValue();
            }
            if (value == null) {
                if (parameter.required()) {
                    throw new ToolInputException("Missing required argument '" + parameter.name() + "' (" + parameter.type() + ")");
                }
                continue;
            }
            checked.put(parameter.name(), coerce(parameter.name(), parameter.type(), parameter.allowedValues(), value,
                    ToolInputException::new));
        }
        return checked;
    }

    public static Builder builder(String name, String connection) {
        return new Builder(name, connection);
    }

    static void requireIdentifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must be letters, digits and underscores, starting with a letter or underscore: " + value);
        }
    }

    private static JsonNode coerce(String name, String type, List<String> allowed, Object value,
                                   java.util.function.Function<String, RuntimeException> error) {
        var nodes = ProcedureTemplates.JSON.getNodeFactory();
        String text = value instanceof String s ? s.strip() : null;
        switch (type) {
            case "string" -> {
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    throw error.apply("Argument '" + name + "' must be a string");
                }
                String s = String.valueOf(value);
                if (!allowed.isEmpty() && !allowed.contains(s)) {
                    throw error.apply("Argument '" + name + "' must be one of " + allowed + ", not '" + s + "'");
                }
                return nodes.stringNode(s);
            }
            case "integer" -> {
                if (value instanceof Number n && !(value instanceof Double || value instanceof Float || value instanceof BigDecimal)) {
                    return nodes.numberNode(n.longValue());
                }
                if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && Math.abs(n.doubleValue()) < 1e15) {
                    return nodes.numberNode(n.longValue());
                }
                if (text != null && text.matches("-?\\d{1,18}")) {
                    return nodes.numberNode(Long.parseLong(text));
                }
                throw error.apply("Argument '" + name + "' must be a whole number");
            }
            case "number" -> {
                try {
                    if (value instanceof Number n) {
                        return nodes.numberNode(new BigDecimal(n.toString()));
                    }
                    if (text != null && !text.isEmpty()) {
                        return nodes.numberNode(new BigDecimal(text));
                    }
                } catch (NumberFormatException e) {
                    // fall through
                }
                throw error.apply("Argument '" + name + "' must be a number");
            }
            default -> {
                if (value instanceof Boolean b) {
                    return nodes.booleanNode(b);
                }
                if (text != null && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
                    return nodes.booleanNode(Boolean.parseBoolean(text));
                }
                throw error.apply("Argument '" + name + "' must be true or false");
            }
        }
    }

    private static List<Parameter> parseSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        for (String key : schema.keySet()) {
            if (!Set.of("type", "properties", "required", "description", "additionalProperties").contains(key)) {
                throw new IllegalArgumentException("inputSchema: '" + key + "' isn't supported");
            }
        }
        if (schema.get("type") != null && !"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException("inputSchema must be of type object");
        }
        if (schema.get("additionalProperties") != null && !Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            throw new IllegalArgumentException("inputSchema: unknown arguments are always refused (additionalProperties must be false if given)");
        }
        Object props = schema.getOrDefault("properties", Map.of());
        Object req = schema.getOrDefault("required", List.of());
        if (!(props instanceof Map<?, ?> properties) || !(req instanceof List<?> required)) {
            throw new IllegalArgumentException("inputSchema needs 'properties' (an object) and 'required' (a list)");
        }
        List<Parameter> parameters = new ArrayList<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> property)) {
                throw new IllegalArgumentException("inputSchema: property '" + name + "' must be an object");
            }
            for (Object key : property.keySet()) {
                if (!Set.of("type", "description", "enum", "default").contains(String.valueOf(key))) {
                    throw new IllegalArgumentException("inputSchema: property '" + name + "' uses '" + key
                            + "', which isn't supported (only type, description, enum and default)");
                }
            }
            List<String> allowed = new ArrayList<>();
            if (property.get("enum") instanceof List<?> values) {
                values.forEach(v -> allowed.add(String.valueOf(v)));
            } else if (property.get("enum") != null) {
                throw new IllegalArgumentException("inputSchema: '" + name + "'.enum must be a list");
            }
            Object type = property.get("type");
            parameters.add(new Parameter(name, type == null ? null : String.valueOf(type),
                    property.get("description") == null ? "" : String.valueOf(property.get("description")),
                    required.contains(name), allowed, property.get("default")));
        }
        for (Object name : required) {
            if (!properties.containsKey(name)) {
                throw new IllegalArgumentException("inputSchema: required '" + name + "' isn't a property");
            }
        }
        return List.copyOf(parameters);
    }

    private static void checkReferences(List<Parameter> parameters, List<ProcedureStep> steps, String output) {
        Map<String, Parameter> byName = new HashMap<>();
        parameters.forEach(p -> byName.put(p.name(), p));
        Map<String, Set<String>> kept = new HashMap<>();
        for (ProcedureStep step : steps) {
            if (byName.containsKey(step.name()) || kept.containsKey(step.name())) {
                throw new IllegalArgumentException("Step name '" + step.name() + "' is used twice (steps and parameters need different names)");
            }
            String where = "Step '" + step.name() + "'";
            for (ProcedureTemplates.Ref ref : refs(step.path(), where + " path")) {
                check(ref, byName, kept, where + " path");
                Parameter p = ref.step() == null ? byName.get(ref.name()) : null;
                if (p != null && !p.required() && p.defaultValue() == null) {
                    throw new IllegalArgumentException(where + " path uses {{" + ref + "}}, which is optional; "
                            + "paths need required parameters (or a default)");
                }
            }
            List<ProcedureStep.Entry> entries = new ArrayList<>(step.query());
            entries.addAll(step.form());
            for (ProcedureStep.Entry entry : entries) {
                for (ProcedureTemplates.Ref ref : refs(entry.value(), where + " '" + entry.name() + "'")) {
                    check(ref, byName, kept, where + " '" + entry.name() + "'");
                }
            }
            if (step.body() != null) {
                List<ProcedureTemplates.Ref> refs = step.bodyType() == ApiBodyType.JSON
                        ? jsonRefs(ProcedureTemplates.parseJson(step.body(), where + " body"), where + " body")
                        : refs(step.body(), where + " body");
                for (ProcedureTemplates.Ref ref : refs) {
                    check(ref, byName, kept, where + " body");
                }
            }
            kept.put(step.name(), step.keep().keySet());
        }
        if (output != null) {
            for (ProcedureTemplates.Ref ref : jsonRefs(ProcedureTemplates.parseJson(output, "output"), "output")) {
                check(ref, byName, kept, "output");
            }
        }
    }

    private static List<ProcedureTemplates.Ref> refs(String template, String where) {
        try {
            return ProcedureTemplates.refs(template);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage());
        }
    }

    private static List<ProcedureTemplates.Ref> jsonRefs(JsonNode template, String where) {
        try {
            return ProcedureTemplates.jsonRefs(template);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage());
        }
    }

    private static void check(ProcedureTemplates.Ref ref, Map<String, Parameter> parameters, Map<String, Set<String>> kept,
                              String where) {
        if (ref.step() == null) {
            if (!parameters.containsKey(ref.name())) {
                throw new IllegalArgumentException(where + " uses {{" + ref + "}}, which isn't a parameter"
                        + (kept.containsKey(ref.name()) ? " (for a step's value, write {{" + ref.name() + ".value_name}})" : ""));
            }
        } else if (!kept.containsKey(ref.step())) {
            throw new IllegalArgumentException(where + " uses {{" + ref + "}}, but no earlier step is called '" + ref.step() + "'");
        } else if (!kept.get(ref.step()).contains(ref.name())) {
            throw new IllegalArgumentException(where + " uses {{" + ref + "}}, but step '" + ref.step() + "' doesn't keep '"
                    + ref.name() + "' (it keeps: " + (kept.get(ref.step()).isEmpty() ? "nothing" : String.join(", ", kept.get(ref.step()))) + ")");
        }
    }

    public static final class Builder {

        private final String name;
        private final String connection;
        private String description = "";
        private final List<Parameter> parameters = new ArrayList<>();
        private final List<ProcedureStep> steps = new ArrayList<>();
        private String output;

        private Builder(String name, String connection) {
            this.name = name;
            this.connection = connection;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        public Builder parameter(Parameter parameter) {
            parameters.add(Objects.requireNonNull(parameter, "parameter"));
            return this;
        }

        public Builder step(ProcedureStep step) {
            steps.add(Objects.requireNonNull(step, "step"));
            return this;
        }

        /** A JSON template for the result; without one, the last step's response body is returned. */
        public Builder output(String jsonTemplate) {
            this.output = jsonTemplate;
            return this;
        }

        public Procedure build() {
            return new Procedure(name, 0, connection, description, schema(parameters), steps, output, null);
        }
    }
}
