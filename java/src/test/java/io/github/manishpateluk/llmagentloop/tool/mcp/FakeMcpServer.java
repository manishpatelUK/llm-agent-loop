package io.github.manishpateluk.llmagentloop.tool.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A minimal MCP server for tests, speaking stdio. Its tools: {@code echo} (returns its text),
 * {@code fail} (an {@code isError} result), and — on a second page of {@code tools/list} — a tool
 * with a name that isn't provider-safe. It also misbehaves the way real servers sometimes do:
 * logs a non-JSON line to stdout, sends a notification, and pings the client.
 */
public final class FakeMcpServer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        out.println("fake server starting (not JSON)");
        String line;
        while ((line = in.readLine()) != null) {
            JsonNode message = JSON.readTree(line);
            if (!message.has("method") || !message.has("id")) {
                continue; // notifications and our ping's reply
            }
            ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", message.get("id"));
            ObjectNode result = response.putObject("result");
            switch (message.get("method").asString()) {
                case "initialize" -> {
                    result.put("protocolVersion", message.path("params").path("protocolVersion").asString());
                    result.putObject("capabilities").putObject("tools");
                    result.putObject("serverInfo").put("name", "fake").put("version", "0");
                    out.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":\"info\",\"data\":\"hi\"}}");
                    out.println("{\"jsonrpc\":\"2.0\",\"id\":\"server-1\",\"method\":\"ping\"}");
                }
                case "tools/list" -> {
                    ArrayNode tools = result.putArray("tools");
                    if (!message.path("params").has("cursor")) {
                        ObjectNode echo = tools.addObject().put("name", "echo").put("description", "Echo text back");
                        ObjectNode schema = echo.putObject("inputSchema").put("type", "object");
                        schema.putObject("properties").putObject("text").put("type", "string");
                        schema.putArray("required").add("text");
                        tools.addObject().put("name", "fail").put("description", "Always fails");
                        result.put("nextCursor", "page2");
                    } else {
                        tools.addObject().put("name", "weird.name/v2").put("description", "");
                    }
                }
                case "tools/call" -> {
                    String name = message.path("params").path("name").asString();
                    switch (name) {
                        case "echo" -> result.putArray("content").addObject().put("type", "text")
                                .put("text", "echo: " + message.path("params").path("arguments").path("text").asString());
                        case "fail" -> {
                            result.putArray("content").addObject().put("type", "text").put("text", "it broke");
                            result.put("isError", true);
                        }
                        case "weird.name/v2" -> result.putObject("structuredContent").put("ok", true);
                        default -> {
                            response.remove("result");
                            response.putObject("error").put("code", -32602).put("message", "Unknown tool: " + name);
                        }
                    }
                }
                default -> {
                    response.remove("result");
                    response.putObject("error").put("code", -32601).put("message", "Method not found");
                }
            }
            out.println(JSON.writeValueAsString(response));
        }
    }
}
