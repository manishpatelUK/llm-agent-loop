package io.github.manishpateluk.llmagentloop.workspace;

/**
 * Caps on one scope's workspace, enforced by {@link ScopedWorkspace} on every write whatever the
 * backing {@link Workspace} is — so a runaway agent can't fill your storage.
 *
 * @param maxFiles      files per scope partition
 * @param maxFileBytes  size of any one file
 * @param maxTotalBytes combined size of every file in the partition
 */
public record WorkspaceLimits(int maxFiles, long maxFileBytes, long maxTotalBytes) {

    /** 1,000 files, 10 MB per file, 100 MB in total. */
    public static final WorkspaceLimits DEFAULT = new WorkspaceLimits(1_000, 10L * 1024 * 1024, 100L * 1024 * 1024);

    public WorkspaceLimits {
        if (maxFiles <= 0 || maxFileBytes <= 0 || maxTotalBytes <= 0) {
            throw new IllegalArgumentException("Workspace limits must all be positive");
        }
    }
}
