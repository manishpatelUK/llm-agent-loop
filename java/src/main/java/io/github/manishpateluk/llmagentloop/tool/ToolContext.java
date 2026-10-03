package io.github.manishpateluk.llmagentloop.tool;

import io.github.manishpateluk.llmagentloop.MessageType;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.ScopedMemory;
import io.github.manishpateluk.llmagentloop.workspace.ScopedWorkspace;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Everything a {@link ToolHandler} knows about the run calling it. Because one agent serves many
 * users, this — not the model's arguments — is where a tool learns whose data it's working with:
 * {@link #memory()} and {@link #workspace()} are already bound to the run's {@link Scope}, so a
 * tool can't reach another user's data even if the model asks it to.
 *
 * @param executionId the run's id, matching its {@code Execution} and {@code AgentMessage}s
 * @param thread      which thread of the run made the call
 * @param scope       the run's full scope (tenant, user, session), e.g. for looking up the
 *                    current tenant's credentials
 * @param memory      the run's memory, at its configured level
 * @param workspace   the run's workspace, at its configured level
 * @param status      sends a status update to the run's {@code onMessage}, attributed to this
 *                    call's thread — see {@link #report}; {@code null} means discard them
 */
public record ToolContext(
        UUID executionId, int thread, Scope scope, ScopedMemory memory, ScopedWorkspace workspace,
        BiConsumer<MessageType, String> status) {

    public ToolContext {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(workspace, "workspace");
        status = status == null ? (type, message) -> { } : status;
    }

    /** A context whose status updates are discarded — e.g. for calling a handler directly in tests. */
    public ToolContext(UUID executionId, int thread, Scope scope, ScopedMemory memory, ScopedWorkspace workspace) {
        this(executionId, thread, scope, memory, workspace, null);
    }

    /** Reports progress from inside a long-running tool, e.g. "waiting for the user" or a delegated agent's steps. */
    public void report(MessageType type, String message) {
        status.accept(type, message);
    }
}
