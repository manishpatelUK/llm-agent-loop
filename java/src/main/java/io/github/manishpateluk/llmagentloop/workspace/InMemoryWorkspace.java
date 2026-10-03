package io.github.manishpateluk.llmagentloop.workspace;

import io.github.manishpateluk.llmagentloop.Scope;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link Workspace} held entirely in this process's heap: lost on restart, so meant for
 * development, tests, and single-process tools — not as a production backend. Bounded per scope
 * by the {@link WorkspaceLimits} its {@link ScopedWorkspace} views enforce.
 */
public final class InMemoryWorkspace implements Workspace {

    private final Map<Scope, Map<String, WorkspaceFile>> files = new ConcurrentHashMap<>();

    @Override
    public Optional<WorkspaceFile> read(Scope scope, String path) {
        return Optional.ofNullable(files.getOrDefault(scope, Map.of()).get(path));
    }

    @Override
    public void write(Scope scope, WorkspaceFile file) {
        files.computeIfAbsent(scope, key -> new ConcurrentHashMap<>()).put(file.path(), file);
    }

    @Override
    public boolean delete(Scope scope, String path) {
        Map<String, WorkspaceFile> scoped = files.get(scope);
        return scoped != null && scoped.remove(path) != null;
    }

    @Override
    public List<WorkspaceFileInfo> list(Scope scope) {
        return files.getOrDefault(scope, Map.of()).values().stream().map(WorkspaceFile::info).toList();
    }
}
