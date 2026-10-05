package io.github.manishpateluk.llmagentloop.tool;

import io.github.manishpateluk.llmagentloop.MessageType;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.ScopedMemory;
import io.github.manishpateluk.llmagentloop.workspace.ScopedWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;

import java.util.Objects;
import java.util.UUID;

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
 * @param run         how a tool reaches back into its run — see {@link #report} and {@link #showToModel};
 *                    {@code null} means a context not attached to a run (e.g. calling a handler in a test)
 */
public record ToolContext(
        UUID executionId, int thread, Scope scope, ScopedMemory memory, ScopedWorkspace workspace, RunAccess run) {

    /** The run-side callbacks behind {@link #report} and {@link #showToModel}. */
    public interface RunAccess {

        RunAccess DETACHED = new RunAccess() {
            @Override
            public void report(MessageType type, String message) {
            }

            @Override
            public void showToModel(WorkspaceFile file) {
            }
        };

        void report(MessageType type, String message);

        void showToModel(WorkspaceFile file);
    }

    public ToolContext {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(workspace, "workspace");
        run = run == null ? RunAccess.DETACHED : run;
    }

    /** A context not attached to a run: status updates and files to show are discarded — e.g. for calling a handler in a test. */
    public ToolContext(UUID executionId, int thread, Scope scope, ScopedMemory memory, ScopedWorkspace workspace) {
        this(executionId, thread, scope, memory, workspace, null);
    }

    /** Reports progress from inside a long-running tool, e.g. "waiting for the user" or a delegated agent's steps. */
    public void report(MessageType type, String message) {
        run.report(type, message);
    }

    /**
     * Shows {@code file} — an image or PDF — to the model directly from the run's next model call
     * on, so it can look at it rather than read extracted text. Models without vision or file
     * input get it dropped by the router rather than failing.
     */
    public void showToModel(WorkspaceFile file) {
        run.showToModel(Objects.requireNonNull(file, "file"));
    }
}
