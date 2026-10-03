package io.github.manishpateluk.llmagentloop.tool.builtin;

import com.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.MessageType;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code ask_human}: lets the agent stop and ask its user a question — a decision, a missing
 * detail, an approval — and continue with the answer. How the question reaches the user is up to
 * the implementor (a chat message, a push notification, an email), via {@link Handler}.
 *
 * <pre>{@code
 * registry.register(HumanTools.askHuman((question, context) ->
 *         chat.askAndAwaitReply(context.scope().sessionId(), question.text(), question.options(), Duration.ofMinutes(30))));
 * }</pre>
 */
public final class HumanTools {

    public static final String ASK_HUMAN = "ask_human";

    private HumanTools() {
    }

    /**
     * Delivers a question to the user of the run in {@code context} (identify them by
     * {@code context.scope()}) and blocks until they answer. It runs on the run's own virtual
     * thread, so waiting minutes is fine. Return {@link Optional#empty()} if they don't answer in
     * whatever time you allow — the agent is told and carries on without.
     */
    @FunctionalInterface
    public interface Handler {
        Optional<String> ask(Question question, ToolContext context);
    }

    /**
     * @param text    the question, as the agent wrote it
     * @param options suggested answers to offer as choices, if the agent gave any; never {@code null}
     */
    public record Question(String text, List<String> options) {
        public Question {
            Objects.requireNonNull(text, "text");
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public static RegisteredTool askHuman(Handler handler) {
        Objects.requireNonNull(handler, "handler");
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(ASK_HUMAN)
                        .description("Ask the user a question and wait for their answer. Use it when you need a "
                                + "decision, approval, or information only they have — not for things you can find "
                                + "out or reasonably assume. Ask one clear question at a time.")
                        .parameters(ToolSchemas.object(List.of("question"),
                                "question", ToolSchemas.string("The question, written for the user."),
                                "options", ToolSchemas.stringArray("Optional suggested answers to offer as choices.")))
                        .build(),
                (args, context) -> {
                    Question question = new Question(
                            ToolArguments.requireString(args, "question"), ToolArguments.optionalStringList(args, "options"));
                    context.report(MessageType.INFO, "Waiting for the user to answer: " + question.text());
                    Optional<String> answer = handler.ask(question, context);
                    return answer != null && answer.isPresent() && !answer.get().isBlank()
                            ? "The user answered: " + answer.get()
                            : "The user did not answer. Continue using your best judgement, and state any assumptions you make.";
                });
    }
}
