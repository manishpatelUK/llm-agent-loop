package io.github.manishpateluk.llmagentloop.skill;

import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.ToolArguments;
import io.github.manishpateluk.llmagentloop.tool.ToolInputException;
import io.github.manishpateluk.llmagentloop.tool.ToolSchemas;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-demand skills (progressive disclosure): the system prompt carries only a {@link #catalogue}
 * of skill names and one-line descriptions, and the {@code load_skill} tool returns a skill's full
 * instructions when the model decides it needs them. {@code Agent.Builder.onDemandSkill} wires both
 * up; use this class directly to do the same with a plain {@code AgentLoop} — register
 * {@link #loadSkill} and append {@link #catalogue} to the profile's instructions.
 *
 * <p>Skill text is trusted application content, so it isn't labelled as untrusted.
 */
public final class SkillLoader {

    public static final String LOAD_SKILL = "load_skill";

    /** Per-run state key: names of the skills this run has loaded. */
    private static final String LOADED = SkillLoader.class.getName() + ".loaded";

    private SkillLoader() {
    }

    /** The system-prompt section listing {@code skills} by name and description, with how to load them. */
    public static String catalogue(List<Skill> skills) {
        StringBuilder out = new StringBuilder("## Skills you can load\n")
                .append("Their instructions aren't shown yet. Before doing work one of them covers, call ")
                .append(LOAD_SKILL).append(" with its name and follow what it returns.\n");
        for (Skill skill : skills) {
            out.append("\n- ").append(skill.name());
            String description = firstLine(skill.description());
            if (!description.isEmpty()) {
                out.append(": ").append(description);
            }
        }
        return out.toString();
    }

    /**
     * {@code load_skill} over {@code skills}: returns the named skill's instructions. A skill already
     * loaded in this run returns a short reminder rather than its instructions again, and an unknown
     * name is a fixable error listing the valid ones. Names match case-insensitively.
     */
    public static RegisteredTool loadSkill(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            throw new IllegalArgumentException("At least one skill is required");
        }
        Map<String, Skill> byName = new LinkedHashMap<>();
        for (Skill skill : skills) {
            if (byName.put(skill.name().toLowerCase(Locale.ROOT), skill) != null) {
                throw new IllegalArgumentException("Duplicate skill name: " + skill.name());
            }
        }
        List<String> names = skills.stream().map(Skill::name).toList();
        return new RegisteredTool(
                ToolDefinition.builder()
                        .name(LOAD_SKILL)
                        .description("Load a skill's full instructions into the conversation. The skills you can load "
                                + "are listed in your instructions under \"Skills you can load\".")
                        .parameters(ToolSchemas.object(List.of("name"),
                                "name", ToolSchemas.stringEnum("The skill to load.", names)))
                        .build(),
                (args, context) -> {
                    String name = ToolArguments.requireString(args, "name").strip();
                    Skill skill = byName.get(name.toLowerCase(Locale.ROOT));
                    if (skill == null) {
                        throw new ToolInputException("No skill called '" + name + "'; available: " + String.join(", ", names));
                    }
                    Set<String> loaded = context.runState(LOADED, ConcurrentHashMap::newKeySet);
                    if (!loaded.add(skill.name())) {
                        return "The " + skill.name() + " skill is already loaded earlier in this conversation; "
                                + "follow those instructions.";
                    }
                    return skill.instructionsSection();
                });
    }

    private static String firstLine(String text) {
        String stripped = text == null ? "" : text.strip();
        int newline = stripped.indexOf('\n');
        return newline < 0 ? stripped : stripped.substring(0, newline).strip();
    }
}
