package io.github.manishpateluk.llmagentloop.workspace;

import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;
import java.util.Optional;

/**
 * File storage for an agent's working documents (drafts, reports, spreadsheets), partitioned by
 * {@link Scope}: implement this to back it with your own storage (S3, a database, ...).
 * {@link InMemoryWorkspace} is the ready-made option for development and single-process use;
 * {@link #NONE} (the default) holds nothing. This library deliberately ships no local-disk
 * implementation.
 *
 * <p>Implementations stay simple because the safety rules live in front of them, in
 * {@link ScopedWorkspace}: every path an implementation receives is already normalized and
 * relative (no {@code ..}, no absolute paths, no drive letters), and size/count limits are already
 * enforced. As with {@code MemoryStore}, {@code scope} arrives already reduced to the configured
 * level — just store files under it. Calls come from a run's own virtual thread, so blocking I/O
 * is fine; implementations must be safe for concurrent runs.
 *
 * <p>Files are data only: nothing in this library ever executes workspace content.
 */
public interface Workspace {

    /** Holds nothing: reads find nothing, and writing fails with a pointer to configure a real workspace. */
    Workspace NONE = new Workspace() {
        @Override
        public Optional<WorkspaceFile> read(Scope scope, String path) {
            return Optional.empty();
        }

        @Override
        public void write(Scope scope, WorkspaceFile file) {
            throw new IllegalStateException(
                    "No Workspace is configured: pass one to AgentLoop.builder().workspace(...) to use the workspace tools");
        }

        @Override
        public boolean delete(Scope scope, String path) {
            return false;
        }

        @Override
        public List<WorkspaceFileInfo> list(Scope scope) {
            return List.of();
        }
    };

    Optional<WorkspaceFile> read(Scope scope, String path);

    /** Creates or replaces the file at {@code file.path()}. */
    void write(Scope scope, WorkspaceFile file);

    /** {@code false} if there was no file at {@code path}. */
    boolean delete(Scope scope, String path);

    /** Every file under {@code scope}, in any order. */
    List<WorkspaceFileInfo> list(Scope scope);

    /** A view of this workspace bound to {@code scope}, enforcing {@code limits} — which is how runs and tools reach it. */
    default ScopedWorkspace scopedTo(Scope scope, WorkspaceLimits limits) {
        return scopedTo(scope, limits, WorkspaceListener.NONE);
    }

    /** {@link #scopedTo(Scope, WorkspaceLimits)}, telling {@code listener} about every change made through the view. */
    default ScopedWorkspace scopedTo(Scope scope, WorkspaceLimits limits, WorkspaceListener listener) {
        return new ScopedWorkspace(this, scope, limits, listener);
    }
}
