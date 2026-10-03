package io.github.manishpateluk.llmagentloop.tool.mcp;

/**
 * An MCP server couldn't be reached, or broke the protocol — an infrastructure problem, which ends
 * a run like any other unexpected tool failure. (A tool that runs but reports failure is different:
 * that comes back to the model as a fixable error.)
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
