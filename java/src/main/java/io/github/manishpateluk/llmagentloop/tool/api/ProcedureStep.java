package io.github.manishpateluk.llmagentloop.tool.api;

import tools.jackson.core.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One HTTP call in a {@link Procedure}. The path, query values, body and form values are
 * templates: {@code {{name}}} is a parameter of the procedure, {@code {{step.value}}} a value an
 * earlier step kept. Values substituted into the path are percent-encoded as a single segment, so
 * they can't add {@code /}, {@code ?} or {@code ..} structure; a JSON body is filled in as JSON
 * (a whole-string {@code "{{count}}"} becomes the number itself), never by pasting text.
 *
 * <pre>{@code
 * ProcedureStep find = ProcedureStep.builder("find", "GET", "/contacts")
 *         .query("email", "{{email}}")
 *         .keep("id", "/data/0/id")            // a JSON Pointer into the response
 *         .build();
 * ProcedureStep note = ProcedureStep.builder("note", "POST", "/contacts/{{find.id}}/notes")
 *         .body("{\"text\": \"{{text}}\"}")
 *         .build();
 * }</pre>
 *
 * @param name     the step's name, used by later steps' templates ({@code {{name.value}}})
 * @param method   GET, HEAD, POST, PUT, PATCH or DELETE
 * @param path     path template under the connection's base URL, starting with {@code /}
 * @param query    query parameters; one whose value is just {@code {{param}}} is left out when that optional parameter isn't given
 * @param bodyType how the body is encoded; {@code null} when there's no body. Defaults to
 *                 {@link ApiBodyType#FORM} when {@code form} is given, otherwise {@link ApiBodyType#JSON}
 * @param body     JSON template (or plain-text template for {@link ApiBodyType#TEXT}); {@code null} for none
 * @param form     form fields for {@link ApiBodyType#FORM}; left out like query parameters when optional and not given
 * @param keep     values to keep from the (JSON) response for later steps and the output: name → JSON Pointer
 */
public record ProcedureStep(String name, String method, String path, List<Entry> query, ApiBodyType bodyType, String body,
                            List<Entry> form, Map<String, String> keep) {

    /** A name and a template value. */
    public record Entry(String name, String value) {

        public Entry {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Query and form entries need a name");
            }
            value = value == null ? "" : value;
        }
    }

    public ProcedureStep {
        Procedure.requireIdentifier(name, "Step name");
        method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
        if (!ApiConnection.ALL_METHODS.contains(method)) {
            throw new IllegalArgumentException("Step '" + name + "': unsupported method " + method);
        }
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Step '" + name + "': the path must start with '/'");
        }
        query = query == null ? List.of() : List.copyOf(query);
        form = form == null ? List.of() : List.copyOf(form);
        body = body == null || body.isBlank() ? null : body;
        if (!form.isEmpty()) {
            if (bodyType != null && bodyType != ApiBodyType.FORM) {
                throw new IllegalArgumentException("Step '" + name + "': form fields need body type FORM");
            }
            if (body != null) {
                throw new IllegalArgumentException("Step '" + name + "': give either form fields or a body, not both");
            }
            bodyType = ApiBodyType.FORM;
        } else if (body != null) {
            if (bodyType == ApiBodyType.FORM) {
                throw new IllegalArgumentException("Step '" + name + "': form bodies are given as form fields, not body text");
            }
            bodyType = bodyType == null ? ApiBodyType.JSON : bodyType;
        } else {
            bodyType = null;
        }
        if (bodyType != null && ApiConnection.READ_METHODS.contains(method)) {
            throw new IllegalArgumentException("Step '" + name + "': " + method + " requests can't have a body");
        }
        Map<String, String> kept = new LinkedHashMap<>();
        if (keep != null) {
            for (Map.Entry<String, String> entry : keep.entrySet()) {
                Procedure.requireIdentifier(entry.getKey(), "Step '" + name + "': kept value name");
                String pointer = Objects.requireNonNull(entry.getValue(), "pointer");
                try {
                    JsonPointer.compile(pointer);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Step '" + name + "': '" + pointer + "' isn't a JSON Pointer like \"/data/0/id\"");
                }
                kept.put(entry.getKey(), pointer);
            }
        }
        keep = Collections.unmodifiableMap(kept);
    }

    /** Whether this step changes something: any method but GET and HEAD. */
    public boolean writes() {
        return !ApiConnection.READ_METHODS.contains(method);
    }

    public static Builder builder(String name, String method, String path) {
        return new Builder(name, method, path);
    }

    public static final class Builder {

        private final String name;
        private final String method;
        private final String path;
        private final List<Entry> query = new ArrayList<>();
        private final List<Entry> form = new ArrayList<>();
        private final Map<String, String> keep = new LinkedHashMap<>();
        private ApiBodyType bodyType;
        private String body;

        private Builder(String name, String method, String path) {
            this.name = name;
            this.method = method;
            this.path = path;
        }

        public Builder query(String name, String valueTemplate) {
            query.add(new Entry(name, valueTemplate));
            return this;
        }

        /** A JSON body template. */
        public Builder body(String jsonTemplate) {
            this.body = jsonTemplate;
            return this;
        }

        /** A body template of the given type ({@link ApiBodyType#JSON} or {@link ApiBodyType#TEXT}). */
        public Builder body(ApiBodyType type, String template) {
            this.bodyType = type;
            this.body = template;
            return this;
        }

        /** A form field; the body becomes {@link ApiBodyType#FORM}. */
        public Builder form(String name, String valueTemplate) {
            form.add(new Entry(name, valueTemplate));
            return this;
        }

        /** Keeps the value at {@code jsonPointer} in the response as {@code {{step.name}}}. */
        public Builder keep(String name, String jsonPointer) {
            keep.put(name, jsonPointer);
            return this;
        }

        public ProcedureStep build() {
            return new ProcedureStep(name, method, path, query, bodyType, body, form, keep);
        }
    }
}
