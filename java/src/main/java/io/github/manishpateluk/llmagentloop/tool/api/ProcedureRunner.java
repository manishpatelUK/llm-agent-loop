package io.github.manishpateluk.llmagentloop.tool.api;

import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Runs {@link Procedure}s: checks the arguments, then sends each step in turn through the
 * connection — with the same method, path and credential rules as {@code api_request} — filling
 * its templates from the arguments and earlier steps' kept values. No model is involved, so your
 * own code can run a saved procedure too:
 *
 * <pre>{@code
 * ProcedureRunner runner = new ProcedureRunner(connections);
 * Procedure procedure = procedures.latest(scope.atLevel(ScopeLevel.TENANT), "add_contact_note").orElseThrow();
 * ProcedureResult result = runner.run(scope, procedure, Map.of("email", "ann@example.com", "text", "Called today"));
 * if (!result.succeeded()) {
 *     log.warn("Step {} failed: {}", result.failure().step(), result.failure().reason());
 * }
 * }</pre>
 *
 * <p>Before sending anything, every step is checked against the connection as it is now: its
 * method must still be allowed (so a procedure that writes fails on a connection that has since
 * been made read-only) and its path pattern must be one the connection allows.
 */
public final class ProcedureRunner {

    static final String CALLER = "procedure_run";

    private final ConnectionSource connections;

    public ProcedureRunner(ConnectionSource connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Runs {@code procedure} for {@code scope} (whose credentials are used) with {@code arguments}.
     *
     * @throws ToolInputException if the arguments don't fit its parameters, its connection doesn't
     *                            exist for {@code scope}, or the connection doesn't allow its steps
     * @throws java.io.UncheckedIOException if the API can't be reached
     */
    public ProcedureResult run(Scope scope, Procedure procedure, Map<String, ?> arguments) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(procedure, "procedure");
        Map<String, JsonNode> checked = procedure.checkArguments(arguments);
        ApiConnection connection = connections.find(scope, procedure.connection()).orElseThrow(() -> new ToolInputException(
                "Procedure " + procedure.name() + " runs on connection '" + procedure.connection() + "', which isn't set up for this user"));
        check(connection, procedure);

        Map<String, Map<String, JsonNode>> kept = new LinkedHashMap<>();
        ProcedureTemplates.Values values = new ProcedureTemplates.Values(checked, kept);
        List<ProcedureResult.Step> ran = new ArrayList<>();
        String lastBody = "";
        String lastMediaType = "";
        for (int i = 0; i < procedure.steps().size(); i++) {
            ProcedureStep step = procedure.steps().get(i);
            int number = i + 1;
            String path = null;
            HttpResponse<byte[]> response;
            try {
                path = ProcedureTemplates.path(step.path(), values);
                Map<String, String> query = new LinkedHashMap<>();
                for (ProcedureStep.Entry entry : step.query()) {
                    String value = ProcedureTemplates.value(entry.value(), values);
                    if (value != null) {
                        query.put(entry.name(), value);
                    }
                }
                response = ApiHttp.send(connection, step.method(), path, query, body(step, values), scope, CALLER);
            } catch (ToolInputException e) {
                return failed(procedure, ran, step, number, path, 0, e.getMessage(), "");
            }
            String text = ApiHttp.text(response);
            ran.add(new ProcedureResult.Step(step.name(), step.method(), path, response.statusCode()));
            if (response.statusCode() / 100 != 2) {
                return failed(procedure, ran, step, number, path, response.statusCode(),
                        "The API answered " + response.statusCode() + ".", capped(text, connection));
            }
            if (!step.keep().isEmpty()) {
                JsonNode json;
                try {
                    json = ProcedureTemplates.JSON.readTree(text);
                } catch (JacksonException e) {
                    return failed(procedure, ran, step, number, path, response.statusCode(),
                            "The response isn't JSON, so the values the step keeps can't be read from it.", capped(text, connection));
                }
                Map<String, JsonNode> stepValues = new LinkedHashMap<>();
                for (Map.Entry<String, String> keep : step.keep().entrySet()) {
                    JsonNode value = json.at(keep.getValue());
                    if (value.isMissingNode()) {
                        return failed(procedure, ran, step, number, path, response.statusCode(),
                                "Nothing at " + keep.getValue() + " in the response, for '" + keep.getKey() + "'.", capped(text, connection));
                    }
                    stepValues.put(keep.getKey(), value);
                }
                kept.put(step.name(), stepValues);
            } else {
                kept.put(step.name(), Map.of());
            }
            lastBody = text;
            lastMediaType = ApiHttp.mediaType(response);
        }
        return new ProcedureResult(procedure, ran, capped(output(procedure, values, lastBody, lastMediaType), connection), null);
    }

    /**
     * Refuses {@code procedure} if {@code connection} doesn't allow one of its steps: its method,
     * or its path with each placeholder taken as a plain segment. The real paths are checked again as
     * each step is sent.
     */
    static void check(ApiConnection connection, Procedure procedure) {
        if (!connection.name().equals(procedure.connection())) {
            throw new ToolInputException("Procedure " + procedure.name() + " is for connection '" + procedure.connection()
                    + "', not '" + connection.name() + "'");
        }
        for (ProcedureStep step : procedure.steps()) {
            String where = "Step '" + step.name() + "': ";
            if (!connection.allowedMethods().contains(step.method())) {
                throw new ToolInputException(where + step.method() + " isn't allowed on " + connection.name()
                        + (step.writes() && !connection.allowsWrites() ? ", which is read-only" : "")
                        + "; allowed: " + new TreeSet<>(connection.allowedMethods()));
            }
            String sample = step.path().replaceAll("\\{\\{[^}]*}}", "x");
            try {
                ApiHttp.resolve(connection, sample, Map.of());
            } catch (ToolInputException e) {
                throw new ToolInputException(where + e.getMessage());
            }
        }
    }

    private static ApiHttp.Body body(ProcedureStep step, ProcedureTemplates.Values values) {
        if (step.bodyType() == null) {
            return null;
        }
        return switch (step.bodyType()) {
            case JSON -> new ApiHttp.Body(ApiBodyType.JSON, ProcedureTemplates.JSON.writeValueAsString(
                    ProcedureTemplates.json(ProcedureTemplates.parseJson(step.body(), "body"), values)));
            case TEXT -> new ApiHttp.Body(ApiBodyType.TEXT, ProcedureTemplates.text(step.body(), values));
            case FORM -> {
                List<Map.Entry<String, String>> fields = new ArrayList<>();
                for (ProcedureStep.Entry entry : step.form()) {
                    String value = ProcedureTemplates.value(entry.value(), values);
                    if (value != null) {
                        fields.add(new AbstractMap.SimpleImmutableEntry<>(entry.name(), value));
                    }
                }
                yield new ApiHttp.Body(ApiBodyType.FORM, ApiHttp.formEncode(fields));
            }
        };
    }

    private static String output(Procedure procedure, ProcedureTemplates.Values values, String lastBody, String mediaType) {
        if (procedure.output() != null) {
            JsonNode output = ProcedureTemplates.json(ProcedureTemplates.parseJson(procedure.output(), "output"), values);
            return ProcedureTemplates.JSON.writeValueAsString(output == null ? ProcedureTemplates.JSON.nullNode() : output);
        }
        if (lastBody.isBlank()) {
            return "(empty body)";
        }
        if (mediaType.contains("json")) {
            try {
                return ProcedureTemplates.JSON.writeValueAsString(ProcedureTemplates.JSON.readTree(lastBody));
            } catch (JacksonException e) {
                // Mislabelled JSON: return it as text.
            }
        }
        return lastBody;
    }

    private static ProcedureResult failed(Procedure procedure, List<ProcedureResult.Step> ran, ProcedureStep step, int number,
                                          String path, int status, String reason, String body) {
        return new ProcedureResult(procedure, ran, null, new ProcedureResult.Failure(step.name(), number, step.method(),
                path == null ? step.path() : path, status, reason, body));
    }

    private static String capped(String text, ApiConnection connection) {
        return ApiTools.capped(text, connection.maxResponseChars(), "keep less with the step's 'keep' or an 'output' template");
    }
}
