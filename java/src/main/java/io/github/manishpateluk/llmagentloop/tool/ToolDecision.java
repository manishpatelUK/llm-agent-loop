package io.github.manishpateluk.llmagentloop.tool;

import java.util.Map;
import java.util.Objects;

/**
 * What a {@link ToolInterceptor} decides about a tool call before it runs.
 *
 * @param action    what to do
 * @param arguments for {@link Action#PROCEED_WITH}: the arguments to run the tool with instead
 * @param text      for {@link Action#RESPOND}: the result to give the model; for
 *                  {@link Action#REFUSE}: why the call was refused
 */
public record ToolDecision(Action action, Map<String, Object> arguments, String text) {

    public enum Action {
        /** Run the tool as requested. */
        PROCEED,
        /** Run the tool with different arguments (e.g. redacted, or with defaults filled in). */
        PROCEED_WITH,
        /** Don't run the tool; give the model this result as though it had. */
        RESPOND,
        /** Don't run the tool; tell the model it was refused and why (it continues, as with a {@link ToolInputException}). */
        REFUSE
    }

    private static final ToolDecision PROCEED = new ToolDecision(Action.PROCEED, null, null);

    public ToolDecision {
        Objects.requireNonNull(action, "action");
        arguments = arguments == null ? null : Map.copyOf(arguments);
    }

    public static ToolDecision proceed() {
        return PROCEED;
    }

    public static ToolDecision proceedWith(Map<String, Object> arguments) {
        return new ToolDecision(Action.PROCEED_WITH, Objects.requireNonNull(arguments, "arguments"), null);
    }

    public static ToolDecision respond(String result) {
        return new ToolDecision(Action.RESPOND, null, Objects.requireNonNull(result, "result"));
    }

    public static ToolDecision refuse(String reason) {
        return new ToolDecision(Action.REFUSE, null, Objects.requireNonNull(reason, "reason"));
    }
}
