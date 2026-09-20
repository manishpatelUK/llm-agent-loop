package io.github.manishpateluk.llmagentloop.tool;

import java.util.Map;

/** A registered tool's implementation: takes the model's parsed call arguments, returns a result string. */
@FunctionalInterface
public interface ToolHandler {

    String handle(Map<String, Object> arguments);
}
