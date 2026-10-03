package io.github.manishpateluk.llmagentloop.workspace;

/**
 * A workspace request refused for a reason the model can fix — an invalid path, a missing file,
 * a limit hit. The built-in workspace tools report its message back to the model as the tool
 * result, rather than ending the run.
 */
public class WorkspaceException extends RuntimeException {

    public WorkspaceException(String message) {
        super(message);
    }
}
