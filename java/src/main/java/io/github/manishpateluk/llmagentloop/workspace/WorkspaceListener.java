package io.github.manishpateluk.llmagentloop.workspace;

/**
 * Told about every change made through a {@link ScopedWorkspace} — e.g. so a search index can keep
 * up with the files. Called on the writing thread after the change has been stored; an exception
 * thrown here fails the write's caller, so listeners that must never interfere catch their own.
 */
public interface WorkspaceListener {

    WorkspaceListener NONE = new WorkspaceListener() {
    };

    /** {@code file} was created or replaced. */
    default void written(WorkspaceFile file) {
    }

    /** The file at {@code path} was deleted. */
    default void deleted(String path) {
    }
}
