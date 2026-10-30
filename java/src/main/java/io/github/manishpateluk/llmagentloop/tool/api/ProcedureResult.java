package io.github.manishpateluk.llmagentloop.tool.api;

import java.util.List;
import java.util.Objects;

/**
 * What running a {@link Procedure} did: each step that ran, and either the output or where it
 * stopped. A failed step — an HTTP error, a value it should have kept missing from the response,
 * a path the connection refuses — ends the run; later steps aren't sent.
 *
 * @param procedure the procedure (and version) that ran
 * @param steps     the steps that were sent, in order, including a failed one
 * @param output    the result as JSON text (or the last response's text if it wasn't JSON); {@code null} on failure
 * @param failure   where and why it stopped; {@code null} on success
 */
public record ProcedureResult(Procedure procedure, List<Step> steps, String output, Failure failure) {

    /** A step that was sent: its method, the path it reached and the status that came back. */
    public record Step(String name, String method, String path, int status) {
    }

    /**
     * @param step   the failing step's name
     * @param number its position, from 1
     * @param status the HTTP status, or 0 if the request wasn't sent (e.g. a refused path)
     * @param reason what went wrong
     * @param body   the response body (trimmed), empty if there was none
     */
    public record Failure(String step, int number, String method, String path, int status, String reason, String body) {
    }

    public ProcedureResult {
        Objects.requireNonNull(procedure, "procedure");
        steps = List.copyOf(steps);
        if ((output == null) == (failure == null)) {
            throw new IllegalArgumentException("A result has either an output or a failure");
        }
    }

    public boolean succeeded() {
        return failure == null;
    }

    /** The steps that ran and changed something (non-GET/HEAD) — what a failure may have left done. */
    public List<Step> writesMade() {
        return steps.stream()
                .filter(s -> !ApiConnection.READ_METHODS.contains(s.method()))
                .filter(s -> failure == null || !s.name().equals(failure.step()) || s.status() / 100 == 2)
                .toList();
    }

    /** This result as told to the model. */
    String describe() {
        String label = "Procedure " + procedure.name() + " v" + procedure.version() + " on " + procedure.connection();
        if (succeeded()) {
            return label + ": " + steps.size() + (steps.size() == 1 ? " step" : " steps") + " succeeded ("
                    + String.join(", ", steps.stream().map(ProcedureResult::summary).toList()) + ").\n\n" + output;
        }
        StringBuilder out = new StringBuilder(label).append(" failed at step ").append(failure.number()).append(" of ")
                .append(procedure.steps().size()).append(" (").append(failure.step()).append("): ");
        if (failure.status() > 0) {
            out.append("HTTP ").append(failure.status()).append(' ');
        }
        out.append(failure.method()).append(' ').append(failure.path() == null ? "" : failure.path());
        out.append("\n").append(failure.reason());
        if (!failure.body().isBlank()) {
            out.append("\n\n").append(failure.body());
        }
        List<Step> before = steps.stream().filter(s -> !s.name().equals(failure.step())).toList();
        if (!before.isEmpty()) {
            out.append("\n\nSteps before it ran: ")
                    .append(String.join(", ", before.stream().map(ProcedureResult::summary).toList())).append('.');
        }
        List<Step> writes = writesMade();
        if (!writes.isEmpty()) {
            out.append(" They made changes, so don't simply run the whole procedure again.");
        }
        out.append("\nLater steps weren't run. If the procedure itself is wrong, look at it with procedure_get, check the "
                + "failing call with api_request, and save the fix with procedure_save (a new version).");
        return out.toString();
    }

    private static String summary(Step step) {
        return step.name() + ": " + step.method() + " " + step.path() + " " + step.status();
    }
}
