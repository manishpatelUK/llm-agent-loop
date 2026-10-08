package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import io.github.manishpateluk.llmagentloop.MessageType;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code delegate_to_agent}: hands a self-contained task to another agent and returns its answer.
 * Unlike the loop's own {@code spawn_sub_task} (the same agent splitting its work), this reaches a
 * <em>different</em> agent with its own instructions and tools.
 *
 * <p>Delegation runs synchronously on the delegating run's thread. Chains are capped at
 * {@link #MAX_DEPTH} levels, so two agents that delegate to each other can't recurse forever.
 */
public final class DelegationTools {

    public static final String DELEGATE = "delegate_to_agent";
    public static final int MAX_DEPTH = 3;

    /** {@code delegate_to_agent}'s own tool timeout: the delegated agent has its own step and time limits too. */
    public static final Duration TIMEOUT = Duration.ofHours(1);

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /** How deep in a delegation chain the current thread is; delegated runs execute on the delegating thread. */
    private static final ScopedValue<Integer> DEPTH = ScopedValue.newInstance();

    /** Longest delegate description shown in the tool's roster; longer ones are cut at a word. */
    public static final int MAX_SUMMARY_CHARS = 160;

    private DelegationTools() {
    }

    /** A delegate's description as one roster line: its first line, cut to {@link #MAX_SUMMARY_CHARS}. */
    static String summary(String description) {
        String text = description == null ? "" : description.strip();
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline).strip();
        }
        if (text.length() <= MAX_SUMMARY_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf(' ', MAX_SUMMARY_CHARS - 1);
        return text.substring(0, cut > MAX_SUMMARY_CHARS / 2 ? cut : MAX_SUMMARY_CHARS - 1).strip() + "\u2026";
    }

    public static RegisteredTool delegateToAgent(List<AgentDelegate> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("At least one delegate is required");
        }
        Map<String, AgentDelegate> byName = new LinkedHashMap<>();
        for (AgentDelegate delegate : delegates) {
            if (!NAME.matcher(delegate.name()).matches()) {
                throw new IllegalArgumentException("Delegate name must match " + NAME + ": " + delegate.name());
            }
            if (byName.put(delegate.name(), delegate) != null) {
                throw new IllegalArgumentException("Duplicate delegate name: " + delegate.name());
            }
        }

        // One compact line per delegate: with dozens of them, this list is most of the tool's size.
        StringBuilder roster = new StringBuilder();
        byName.values().forEach(d -> roster.append("\n- ").append(d.name()).append(": ").append(summary(d.description())));

        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(DELEGATE)
                        .description("Hand a self-contained task to a specialist agent and get its result back. "
                                + "Give it everything it needs in the task: it doesn't see this conversation. Agents:" + roster)
                        .parameters(ToolSchemas.object(List.of("agent", "task"),
                                "agent", ToolSchemas.stringEnum("Which agent to delegate to.", List.copyOf(byName.keySet())),
                                "task", ToolSchemas.string("The complete task, including any context and what to return.")))
                        .build(),
                (args, context) -> {
                    String name = ToolArguments.requireString(args, "agent");
                    AgentDelegate delegate = byName.get(name);
                    if (delegate == null) {
                        throw new ToolInputException("No agent called '" + name + "'; available: "
                                + String.join(", ", byName.keySet()));
                    }
                    int depth = DEPTH.orElse(0) + 1;
                    if (depth > MAX_DEPTH) {
                        throw new ToolInputException("Delegation is already " + MAX_DEPTH
                                + " levels deep; finish this task yourself instead of delegating further");
                    }
                    String task = ToolArguments.requireString(args, "task");
                    context.report(MessageType.PROGRESS, "Delegating to " + name + ": " + task);
                    return ScopedValue.where(DEPTH, depth).call(() -> delegate.run(task, context));
                }, TIMEOUT);
    }
}
