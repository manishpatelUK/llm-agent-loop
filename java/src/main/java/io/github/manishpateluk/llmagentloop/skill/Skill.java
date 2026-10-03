package io.github.manishpateluk.llmagentloop.skill;

import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;

import java.util.List;
import java.util.Objects;

/**
 * A reusable capability an agent can be given: tools, plus the know-how to use them well. The
 * instructions are added to the agent's system instructions (under a heading with the skill's
 * name) and the tools to its tool set. {@link Skills} has ready-made ones; make your own for a
 * domain ("UK payroll", "our CRM") by pairing your tools with guidance on when and how to use them.
 *
 * @param name         a short title, e.g. {@code "Spreadsheets"}
 * @param description  one line on what it enables
 * @param instructions Markdown guidance for the model
 * @param tools        the tools it brings; may be empty for a purely instructional skill
 */
public record Skill(String name, String description, String instructions, List<RegisteredTool> tools) {

    public Skill {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        instructions = instructions == null ? "" : instructions;
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** This skill as a section of an agent's instructions. */
    public String instructionsSection() {
        StringBuilder out = new StringBuilder("### ").append(name).append('\n');
        if (!description.isBlank()) {
            out.append(description.strip()).append("\n\n");
        }
        return out.append(instructions.strip()).toString().strip();
    }
}
