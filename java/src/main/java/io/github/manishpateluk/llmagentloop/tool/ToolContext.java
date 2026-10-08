package io.github.manishpateluk.llmagentloop.tool;

import io.github.manishpateluk.llmagentloop.MessageType;
import io.github.manishpateluk.llmagentloop.Scope;
import io.github.manishpateluk.llmagentloop.memory.ScopedMemory;
import io.github.manishpateluk.llmagentloop.search.KnowledgeSearch;
import io.github.manishpateluk.llmagentloop.workspace.ScopedWorkspace;
import io.github.manishpateluk.llmagentloop.workspace.WorkspaceFile;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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

        /** Where the run has read outside content from so far; empty if it hasn't. */
        default Set<String> untrustedSources() {
            return Set.of();
        }

        /** Semantic search over the run's workspace; {@link KnowledgeSearch#NONE} if it isn't configured. */
        default KnowledgeSearch knowledge() {
            return KnowledgeSearch.NONE;
        }

        /** The run's tool state behind {@link ToolContext#runState}; a context without a run gets a fresh, unshared map. */
        default Map<String, Object> state() {
            return new ConcurrentHashMap<>();
        }
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

    /**
     * Where this run has read outside content from so far — tools marked {@code untrustedOutput}
     * (e.g. {@code web_fetch}, {@code email_read}) and attachments. Empty if it hasn't. Interceptors
     * use it to treat high-impact actions more carefully once instructions could have been injected
     * (see {@code UntrustedContentGuard}).
     */
    public Set<String> untrustedSources() {
        return run.untrustedSources();
    }

    /**
     * Semantic search over this run's workspace files, bound to its scope — see
     * {@code SemanticSearch}. Searching fails with a clear message if semantic search isn't configured.
     */
    public KnowledgeSearch knowledge() {
        return run.knowledge();
    }

    /**
     * State a tool keeps for the rest of this run, shared by all its tool calls (including in
     * sub-tasks and plan steps, but not in delegated agents' runs): the value under {@code key},
     * created with {@code initial} on first use. Values must be safe for concurrent use. Prefix keys
     * with your tool's name to avoid clashes.
     */
    @SuppressWarnings("unchecked")
    public <T> T runState(String key, Supplier<T> initial) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(initial, "initial");
        return (T) run.state().computeIfAbsent(key, k -> initial.get());
    }
}
