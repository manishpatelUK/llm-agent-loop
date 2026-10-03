package io.github.manishpateluk.llmagentloop.tool.mcp;

import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/** Carries JSON-RPC 2.0 messages to and from one MCP server. */
interface McpTransport extends AutoCloseable {

    /** Sends a request (which carries an {@code id}) and returns the matching response message. */
    ObjectNode request(ObjectNode message, Duration timeout);

    /** Sends a notification (no {@code id}, no response). */
    void notify(ObjectNode message);

    /** Called once the server has told us which protocol version the session uses. */
    default void protocolVersion(String version) {
    }

    @Override
    void close();
}
