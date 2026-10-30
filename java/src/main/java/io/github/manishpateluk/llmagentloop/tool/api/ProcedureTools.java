package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.ScopeLevel;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * {@code procedure_list}, {@code procedure_get}, {@code procedure_run} and {@code procedure_save}:
 * the model saves what it worked out against an API as a {@link Procedure}, and runs it again
 * later with new arguments in one call, every step in code.
 *
 * <pre>{@code
 * registry.registerAll(ApiTools.all(connections))                                    // api_connections, api_request, api_describe
 *         .registerAll(ProcedureTools.all(connections, new InMemoryProcedureStore())); // or your own ProcedureStore
 * // or, on an Agent: Skills.apiProcedures(connections, store)
 * }</pre>
 *
 * <p>Procedures are shared at {@link ScopeLevel#TENANT} unless you choose another level: the
 * store sees {@code context.scope().atLevel(level)}, never a scope from the model's arguments.
 * {@code procedure_save} checks the procedure against the connection (methods, so a procedure
 * that writes needs a connection that allows writes, and path patterns); {@code procedure_run}
 * checks again, since the connection may have changed since.
 *
 * <p>{@code procedure_run} declares a write ({@code RegisteredTool.withWriteCheck}) when the
 * procedure it runs writes, and {@code procedure_save} when the procedure it saves does, so
 * {@code UntrustedContentGuard} and approval interceptors ({@code context.writes(call)}) treat them
 * like an {@code api_request} POST. Saved procedures are written by the model and may carry text
 * from outside sources, so what all four return is marked as untrusted content.
 */
public final class ProcedureTools {

    public static final String LIST = "procedure_list";
    public static final String GET = "procedure_get";
    public static final String RUN = "procedure_run";
    public static final String SAVE = "procedure_save";

    private ProcedureTools() {
    }

    /** All four tools, with procedures shared across each tenant. */
    public static List<RegisteredTool> all(ConnectionSource connections, ProcedureStore store) {
        return all(connections, store, ScopeLevel.TENANT);
    }

    /** All four tools, with procedures shared at {@code level}. */
    public static List<RegisteredTool> all(ConnectionSource connections, ProcedureStore store, ScopeLevel level) {
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(level, "level");
        return List.of(list(store, level), get(store, level), run(connections, store, level), save(connections, store, level));
    }

    private static RegisteredTool list(ProcedureStore store, ScopeLevel level) {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(LIST)
                        .description("List saved API procedures: tasks already worked out against an API, which "
                                + RUN + " does in one call. Check here before working a task out from scratch.")
                        .parameters(ToolSchemas.object(List.of(),
                                "query", ToolSchemas.string("Optional words to search names and descriptions for; omit to list the most recent."),
                                "limit", ToolSchemas.integer("Optional maximum to return (1-50, default 20).")))
                        .build(),
                (args, context) -> {
                    List<Procedure> found = store.search(scope(context, level), Objects.requireNonNullElse(
                            ToolArguments.optionalString(args, "query"), ""), ToolArguments.optionalInt(args, "limit", 20, 1, 50));
                    if (found.isEmpty()) {
                        return "No saved procedures" + (ToolArguments.optionalString(args, "query") == null ? "." : " match.");
                    }
                    StringBuilder out = new StringBuilder("Procedures:");
                    for (Procedure p : found) {
                        out.append("\n- ").append(p.name()).append(" v").append(p.version()).append(" (").append(p.connection())
                                .append(", ").append(p.writes() ? "writes" : "read-only").append("): ").append(p.description());
                        List<Procedure.Parameter> parameters = p.parameters();
                        out.append("\n  parameters: ").append(parameters.isEmpty() ? "none"
                                : String.join("; ", parameters.stream().map(Procedure.Parameter::summary).toList()));
                    }
                    return out.append("\n(* = required)").toString();
                })
                .withUntrustedOutput();
    }

    private static RegisteredTool get(ProcedureStore store, ScopeLevel level) {
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(GET)
                        .description("Show a saved procedure's full definition (parameters, steps, output) and its versions, "
                                + "e.g. to repair one that failed.")
                        .parameters(ToolSchemas.object(List.of("name"),
                                "name", ToolSchemas.string("The procedure's name."),
                                "version", ToolSchemas.integer("Optional version; the latest if omitted.")))
                        .build(),
                (args, context) -> {
                    Scope scope = scope(context, level);
                    Procedure procedure = find(store, scope, args);
                    List<Procedure> versions = store.versions(scope, procedure.name());
                    StringBuilder out = new StringBuilder(ProcedureTemplates.JSON.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(procedure));
                    out.append("\n\nVersions: ");
                    out.append(String.join(", ", versions.stream()
                            .map(v -> v.version() + " (" + v.savedAt() + ")" + (v.version() == versions.getLast().version() ? " latest" : ""))
                            .toList()));
                    return out.toString();
                })
                .withUntrustedOutput();
    }

    private static RegisteredTool run(ConnectionSource connections, ProcedureStore store, ScopeLevel level) {
        ProcedureRunner runner = new ProcedureRunner(connections);
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(RUN)
                        .description("Run a saved procedure with arguments: every step runs in one call. Returns its output, "
                                + "or the failing step's status and response so you can fix it.")
                        .parameters(ToolSchemas.object(List.of("name"),
                                "name", ToolSchemas.string("The procedure's name."),
                                "arguments", ToolSchemas.array("Its arguments, e.g. [{\"name\":\"email\",\"value\":\"ann@example.com\"}].",
                                        ApiTools.nameValue()),
                                "version", ToolSchemas.integer("Optional version; the latest if omitted.")))
                        .build(),
                (args, context) -> {
                    Procedure procedure = find(store, scope(context, level), args);
                    return runner.run(context.scope(), procedure, arguments(args)).describe();
                })
                .withUntrustedOutput()
                .withWriteCheck((args, context) -> {
                    try {
                        return find(store, scope(context, level), args).writes();
                    } catch (ToolInputException e) {
                        return false;  // nothing will run
                    }
                });
    }

    private static RegisteredTool save(ConnectionSource connections, ProcedureStore store, ScopeLevel level) {
        Map<String, Object> parameter = ToolSchemas.object(List.of("name", "type"),
                "name", ToolSchemas.string("Parameter name: letters, digits and underscores."),
                "type", ToolSchemas.stringEnum("Its type.", Procedure.TYPES),
                "description", ToolSchemas.string("What it is."),
                "required", ToolSchemas.bool("Whether it must be given (default true)."),
                "allowed_values", ToolSchemas.stringArray("Optional: the only values allowed (strings)."),
                "default", ToolSchemas.string("Optional default value."));
        Map<String, Object> step = ToolSchemas.object(List.of("name", "method", "path"),
                "name", ToolSchemas.string("Step name, used by later templates: {{step_name.value}}."),
                "method", ToolSchemas.stringEnum("HTTP method.", List.copyOf(new TreeSet<>(ApiConnection.ALL_METHODS))),
                "path", ToolSchemas.string("Path template under the base URL, e.g. \"/contacts/{{find.id}}/notes\"."),
                "query", ToolSchemas.array("Query parameters; values are templates.", ApiTools.nameValue()),
                "content_type", ToolSchemas.stringEnum("Body encoding: form for 'form' fields, JSON by default for 'body'.",
                        ApiBodyType.mediaTypes()),
                "body", ToolSchemas.string("Body template: JSON with {{...}} inside strings (\"{{count}}\" alone becomes the value itself), or plain text."),
                "form", ToolSchemas.array("Form fields; values are templates.", ApiTools.nameValue()),
                "keep", ToolSchemas.array("Values to keep from the JSON response for later steps and the output.",
                        ToolSchemas.object(List.of("name", "pointer"),
                                "name", ToolSchemas.string("Name to refer to it by: {{step_name.name}}."),
                                "pointer", ToolSchemas.string("JSON Pointer into the response, e.g. \"/data/0/id\"."))));
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(SAVE)
                        .description("Save a task you have just done successfully against an API as a procedure that "
                                + RUN + " can repeat with new arguments. Templates: {{param}} for a parameter, "
                                + "{{step.value}} for a value an earlier step kept. Saving an existing name makes a new "
                                + "version; earlier versions are kept.")
                        .parameters(ToolSchemas.object(List.of("name", "connection", "description", "steps"),
                                "name", ToolSchemas.string("Short snake_case name saying what it does, e.g. \"add_contact_note\"."),
                                "connection", ToolSchemas.string("The connection it runs on."),
                                "description", ToolSchemas.string("What it does and when to use it."),
                                "parameters", ToolSchemas.array("Its parameters.", parameter),
                                "steps", ToolSchemas.array("The calls, in order.", step),
                                "output", ToolSchemas.string("Optional JSON template for the result, e.g. "
                                        + "{\"note_id\": \"{{add.id}}\"}; the last response's body if omitted.")))
                        .build(),
                (args, context) -> {
                    Procedure procedure = procedure(args);
                    ApiConnection connection = ApiTools.connection(connections, procedure.connection(), context);
                    ProcedureRunner.check(connection, procedure);
                    Procedure saved = store.save(scope(context, level), procedure);
                    StringBuilder out = new StringBuilder("Saved ").append(saved.name()).append(" as version ").append(saved.version())
                            .append(" on ").append(saved.connection()).append(saved.writes() ? " (writes: " + String.join(", ",
                                    saved.steps().stream().filter(ProcedureStep::writes).map(s -> s.method() + " " + s.path()).toList()) + ")"
                                    : " (read-only)").append(". Run it with ").append(RUN).append('.');
                    if (saved.version() > 1) {
                        out.append(" Earlier versions are kept (").append(GET).append(" with a version).");
                    }
                    return out.toString();
                })
                .withUntrustedOutput()
                .withWriteCheck((args, context) -> args.get("steps") instanceof List<?> steps && steps.stream()
                        .anyMatch(s -> !(s instanceof Map<?, ?> m) || !ApiConnection.READ_METHODS.contains(
                                String.valueOf(m.get("method")).toUpperCase(Locale.ROOT))));
    }

    private static Scope scope(ToolContext context, ScopeLevel level) {
        return context.scope().atLevel(level);
    }

    private static Procedure find(ProcedureStore store, Scope scope, Map<String, Object> args) {
        String name = ToolArguments.requireString(args, "name");
        Object versionArg = args.get("version");
        Optional<Procedure> found = versionArg == null ? store.latest(scope, name)
                : store.version(scope, name, ToolArguments.optionalInt(args, "version", 1, 1, Integer.MAX_VALUE));
        return found.orElseThrow(() -> new ToolInputException("No saved procedure called '" + name + "'"
                + (versionArg == null ? "" : " with version " + versionArg) + "; see " + LIST));
    }

    /** {@code arguments} as {@code {name, value}} pairs, or (from code or lenient models) an object. */
    private static Map<String, Object> arguments(Map<String, Object> args) {
        Object value = args.get("arguments");
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> arguments.put(String.valueOf(k), v));
            return arguments;
        }
        for (Map.Entry<String, String> pair : ApiTools.pairs(args, "arguments")) {
            if (arguments.put(pair.getKey(), pair.getValue()) != null) {
                throw new ToolInputException("Argument '" + pair.getKey() + "' is given twice");
            }
        }
        return arguments;
    }

    /** The procedure {@code procedure_save}'s arguments describe. */
    private static Procedure procedure(Map<String, Object> args) {
        String name = ToolArguments.requireString(args, "name");
        try {
            Procedure.Builder builder = Procedure.builder(name, ToolArguments.requireString(args, "connection"))
                    .description(ToolArguments.requireString(args, "description"));
            for (Map<?, ?> p : objects(args, "parameters")) {
                Map<String, Object> parameter = strings(p);
                List<String> allowed = ToolArguments.optionalStringList(parameter, "allowed_values");
                builder.parameter(new Procedure.Parameter(ToolArguments.requireString(parameter, "name"),
                        ToolArguments.requireString(parameter, "type"), ToolArguments.optionalString(parameter, "description"),
                        ToolArguments.optionalBoolean(parameter, "required", true), allowed, parameter.get("default")));
            }
            List<Map<?, ?>> steps = objects(args, "steps");
            if (steps.isEmpty()) {
                throw new ToolInputException("'steps' needs at least one step");
            }
            for (Map<?, ?> s : steps) {
                Map<String, Object> step = strings(s);
                String contentType = ToolArguments.optionalString(step, "content_type");
                ApiBodyType bodyType = contentType == null || contentType.isBlank() ? null : ApiBodyType.ofMediaType(contentType)
                        .orElseThrow(() -> new ToolInputException("'content_type' must be one of " + ApiBodyType.mediaTypes()));
                Map<String, String> keep = new LinkedHashMap<>();
                for (Map<?, ?> k : objects(step, "keep")) {
                    Map<String, Object> kept = strings(k);
                    keep.put(ToolArguments.requireString(kept, "name"), ToolArguments.requireString(kept, "pointer"));
                }
                builder.step(new ProcedureStep(ToolArguments.requireString(step, "name"), ToolArguments.requireString(step, "method"),
                        ToolArguments.requireString(step, "path").strip(), entries(step, "query"), bodyType,
                        ToolArguments.optionalString(step, "body"), entries(step, "form"), keep));
            }
            return builder.output(ToolArguments.optionalString(args, "output")).build();
        } catch (IllegalArgumentException e) {
            throw new ToolInputException(e.getMessage());
        }
    }

    private static List<ProcedureStep.Entry> entries(Map<String, Object> args, String name) {
        return ApiTools.pairs(args, name).stream().map(e -> new ProcedureStep.Entry(e.getKey(), e.getValue())).toList();
    }

    private static List<Map<?, ?>> objects(Map<String, Object> args, String name) {
        Object value = args.get(name);
        List<Map<?, ?>> objects = new ArrayList<>();
        if (value == null) {
            return objects;
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(Map.class::isInstance)) {
            throw new ToolInputException("'" + name + "' must be a list of objects");
        }
        list.forEach(item -> objects.add((Map<?, ?>) item));
        return objects;
    }

    private static Map<String, Object> strings(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
