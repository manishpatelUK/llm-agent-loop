package io.github.manishpateluk.llmagentloop.tool.builtin;

import io.github.manishpateluk.llmagentloop.AgentLoop;
import io.github.manishpateluk.llmagentloop.AgentLoopResult;
import io.github.manishpateluk.llmagentloop.AgentLoopStepLimitExceededException;
import io.github.manishpateluk.llmagentloop.AgentProfile;
import io.github.manishpateluk.llmagentloop.LoopRequest;
import io.github.manishpateluk.llmagentloop.MessageType;
import io.github.manishpateluk.llmagentloop.execution.TerminationReason;
import io.github.manishpateluk.llmagentloop.tool.ToolContext;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;

import java.util.Objects;

/**
 * Another agent that {@code delegate_to_agent} can hand work to — e.g. a cofounder agent
 * delegating to a legal or finance specialist. {@link #of} wraps an {@link AgentLoop} and its
 * {@link AgentProfile}; implement this directly to delegate to something else entirely (a remote
 * agent service, a human team's queue).
 */
public interface AgentDelegate {

    /** How the model refers to this agent; {@code ^[a-zA-Z0-9_-]{1,64}$}. */
    String name();

    /** What this agent is good at — the model chooses delegates by it. */
    String description();

    /**
     * Carries out {@code task} for the user of the run in {@code context} and returns the result
     * as text. Blocks until done. Throw {@link ToolInputException} for a failure the delegating
     * agent could work around (e.g. by splitting the task).
     */
    String run(String task, ToolContext context);

    /**
     * Delegates to {@code loop} running {@code profile}. The delegated run uses the same
     * {@code Scope} as the delegating one — same user, same memory and workspace — and its status
     * updates are forwarded, prefixed with this delegate's name. Its cost and step limits are its
     * own ({@code profile.maxSteps()}); they don't count against the delegating run's.
     */
    static AgentDelegate of(String name, String description, AgentLoop loop, AgentProfile profile) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(loop, "loop");
        return new AgentDelegate() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String run(String task, ToolContext context) {
                AgentLoopResult result;
                try {
                    result = loop.runAndWait(LoopRequest.builder()
                            .prompt(task)
                            .agentProfile(profile)
                            .scope(context.scope())
                            .onMessage(message -> context.report(message.type(), "[" + name + "] " + message.message())));
                } catch (AgentLoopStepLimitExceededException e) {
                    throw new ToolInputException(name + " ran out of steps before finishing; try a smaller, more specific task");
                }
                String answer = result.finalResponse().getContent();
                TerminationReason reason = result.execution().terminationReason();
                if (reason != TerminationReason.COMPLETED) {
                    context.report(MessageType.WARNING, "[" + name + "] stopped early: " + reason);
                    return "(" + name + " stopped early — " + reason + " — so this may be incomplete.)\n" + answer;
                }
                return answer;
            }
        };
    }
}
