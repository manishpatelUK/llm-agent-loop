package io.github.manishpateluk.llmagentloop.agent;

import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.provider.Provider;
import io.github.manishpateluk.llmagentloop.AgentProfile;
import io.github.manishpateluk.llmagentloop.PlanMode;
import io.github.manishpateluk.llmagentloop.skill.Skill;
import io.github.manishpateluk.llmagentloop.skill.SkillLoader;
import io.github.manishpateluk.llmagentloop.tool.RegisteredTool;
import io.github.manishpateluk.llmagentloop.tool.builtin.AgentDelegate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A reusable agent definition: what it is and how it behaves (Markdown), what it knows how to do
 * ({@link Skill}s and tools), and which other agents it can hand work to. An {@code Agent} holds no
 * user data and no infrastructure, so one definition serves every user — run it with an
 * {@link AgentRuntime}, passing each user's {@code Scope}.
 *
 * <p>The definition is Markdown with optional front matter:
 * <pre>{@code
 * ---
 * name: assistant
 * description: A general-purpose business assistant
 * plan_mode: auto            # auto | always_plan | never_plan | recursive_on_each_step
 * max_steps: 40
 * models: anthropic/claude-sonnet-5-5, openai   # preference order; a provider alone lets the router pick its model
 * thinking_level: high       # low | medium | high | max
 * cost_optimized: false
 * ---
 * You are the user's business assistant. You handle admin, finance and product work end to end...
 * }</pre>
 * Everything after the front matter becomes the agent's instructions, verbatim. {@code name} is
 * required (in the front matter or via the builder); it must match {@code ^[a-zA-Z0-9_-]{1,64}$}
 * since other agents delegate to it by name. {@code models}, {@code thinking_level} and
 * {@code cost_optimized} set the agent's default {@link RouterConfig} (also settable with
 * {@link Builder#routerConfig}); a run can still override it with {@code LoopRequest.routerConfig}.
 *
 * <p>Any other front matter keys are your application's own data: they're kept, in order, in
 * {@link #metadata()} and otherwise ignored (e.g. {@code skills: evidence_discipline} for your code to
 * resolve into {@link Builder#onDemandSkill}s). Known keys are still validated, so a bad
 * {@code plan_mode} value fails; {@link Builder#strictFrontMatter()} also rejects unknown keys, to
 * catch misspelled key names.
 *
 * <p>Skills are given two ways. {@link Builder#skill} inlines a skill's instructions in the system
 * prompt on every call. {@link Builder#onDemandSkill} lists only its name and description there, and
 * the model loads the full instructions with {@code load_skill} when it needs them: the way to give
 * an agent dozens of skills or reference documents without paying for them on every call.
 */
public final class Agent {

    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /** Front matter keys this library reads; anything else goes to {@link #metadata()}. */
    static final List<String> KNOWN_KEYS =
            List.of("name", "description", "plan_mode", "max_steps", "models", "thinking_level", "cost_optimized");

    private final String name;
    private final String description;
    private final String instructions;
    private final PlanMode planMode;
    private final int maxSteps;
    private final RouterConfig routerConfig;
    private final List<Skill> skills;
    private final List<Skill> onDemandSkills;
    private final Map<String, String> metadata;
    private final List<RegisteredTool> tools;
    private final List<Agent> delegateAgents;
    private final List<AgentDelegate> delegates;

    private Agent(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.instructions = b.instructions;
        this.planMode = b.planMode;
        this.maxSteps = b.maxSteps;
        this.routerConfig = b.routerConfig();
        this.skills = List.copyOf(b.skills);
        this.onDemandSkills = List.copyOf(b.onDemandSkills);
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata));
        this.tools = List.copyOf(b.tools);
        this.delegateAgents = List.copyOf(b.delegateAgents);
        this.delegates = List.copyOf(b.delegates);
    }

    /** An agent from a Markdown definition with no extra skills or tools. */
    public static Agent fromMarkdown(String markdown) {
        return builder(markdown).build();
    }

    /** Starts from a Markdown definition; add skills, tools and delegates on the builder. */
    public static Builder builder(String markdown) {
        return new Builder(markdown);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** The definition's Markdown body, without front matter. */
    public String instructions() {
        return instructions;
    }

    /** Skills whose instructions are inlined in every call's system prompt. */
    public List<Skill> skills() {
        return skills;
    }

    /** Skills listed in the system prompt by name and description only, loaded by the model with {@code load_skill}. */
    public List<Skill> onDemandSkills() {
        return onDemandSkills;
    }

    /**
     * Front matter keys this library doesn't use, in the order written: your application's own
     * data. Keys are lower-cased; values are as written (unquoted). Unmodifiable; empty if none.
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    /** How this agent's model calls are routed by default; {@code null} for the router's defaults. */
    public RouterConfig routerConfig() {
        return routerConfig;
    }

    public List<RegisteredTool> tools() {
        return tools;
    }

    /** Other agents this one may delegate to, run on the same {@link AgentRuntime}. */
    public List<Agent> delegateAgents() {
        return delegateAgents;
    }

    /** Custom delegates (anything implementing {@link AgentDelegate}). */
    public List<AgentDelegate> delegates() {
        return delegates;
    }

    /**
     * The {@link AgentProfile} each run uses: the definition's instructions, then each inlined
     * skill's guidance, then the catalogue of on-demand skills.
     */
    public AgentProfile profile() {
        StringBuilder text = new StringBuilder(instructions.strip());
        if (!skills.isEmpty()) {
            text.append("\n\n## Skills\n");
            for (Skill skill : skills) {
                if (!skill.instructions().isBlank()) {
                    text.append('\n').append(skill.instructionsSection()).append('\n');
                }
            }
        }
        if (!onDemandSkills.isEmpty()) {
            text.append("\n\n").append(SkillLoader.catalogue(onDemandSkills));
        }
        return AgentProfile.builder()
                .planMode(planMode)
                .maxSteps(maxSteps)
                .instructions(text.toString().strip())
                .routerConfig(routerConfig)
                .build();
    }

    @Override
    public String toString() {
        return "Agent[" + name + "]";
    }

    public static final class Builder {

        private String name;
        private String description = "";
        private final String instructions;
        private PlanMode planMode = PlanMode.AUTO;
        private int maxSteps = 0;
        private RouterConfig explicitRouterConfig;
        private List<RouteEntry> models;
        private ThinkingLevel thinkingLevel;
        private Boolean costOptimized;
        private final List<Skill> skills = new ArrayList<>();
        private final List<Skill> onDemandSkills = new ArrayList<>();
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private boolean strictFrontMatter;
        private final List<RegisteredTool> tools = new ArrayList<>();
        private final List<Agent> delegateAgents = new ArrayList<>();
        private final List<AgentDelegate> delegates = new ArrayList<>();

        private Builder(String markdown) {
            Objects.requireNonNull(markdown, "markdown");
            Parsed parsed = parse(markdown);
            this.instructions = parsed.body();
            Map<String, String> front = parsed.frontMatter();
            for (Map.Entry<String, String> entry : front.entrySet()) {
                switch (entry.getKey()) {
                    case "name" -> name = entry.getValue();
                    case "description" -> description = entry.getValue();
                    case "plan_mode" -> planMode = parsePlanMode(entry.getValue());
                    case "max_steps" -> {
                        try {
                            maxSteps = Integer.parseInt(entry.getValue());
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("max_steps must be a whole number, was \"" + entry.getValue() + "\"");
                        }
                    }
                    case "models" -> models = parseModels(entry.getValue());
                    case "thinking_level" -> thinkingLevel = parseThinkingLevel(entry.getValue());
                    case "cost_optimized" -> costOptimized = parseBoolean("cost_optimized", entry.getValue());
                    default -> metadata.put(entry.getKey(), entry.getValue());
                }
            }
        }

        /**
         * Rejects front matter keys this library doesn't know, instead of keeping them in
         * {@link Agent#metadata()}: for definitions that should carry no application data, so a
         * misspelled key (e.g. {@code plan-mode}) fails rather than being silently kept.
         */
        public Builder strictFrontMatter() {
            this.strictFrontMatter = true;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        public Builder planMode(PlanMode planMode) {
            this.planMode = Objects.requireNonNull(planMode, "planMode");
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        /**
         * The agent's default routing — which providers/models in what order, thinking level, etc.
         * Replaces anything the front matter's {@code models}/{@code thinking_level}/{@code cost_optimized} set.
         */
        public Builder routerConfig(RouterConfig routerConfig) {
            this.explicitRouterConfig = routerConfig;
            return this;
        }

        private RouterConfig routerConfig() {
            if (explicitRouterConfig != null) {
                return explicitRouterConfig;
            }
            if (models == null && thinkingLevel == null && costOptimized == null) {
                return null;
            }
            RouterConfig.RouterConfigBuilder config = RouterConfig.builder().route(models);
            if (thinkingLevel != null) {
                config.thinkingLevel(thinkingLevel);
            }
            if (costOptimized != null) {
                config.costOptimized(costOptimized);
            }
            return config.build();
        }

        public Builder skill(Skill skill) {
            skills.add(Objects.requireNonNull(skill, "skill"));
            return this;
        }

        public Builder skills(Skill... skills) {
            for (Skill skill : skills) {
                skill(skill);
            }
            return this;
        }

        /**
         * Gives this agent {@code skill} on demand: the system prompt lists only its name and
         * description, and the model calls {@code load_skill} to read its instructions when it needs
         * them (each skill at most once per run). Any tools the skill brings are registered from the
         * start, like an inlined skill's: tools are small, it's the instructions that are kept out of
         * the prompt. Skill names must be unique across an agent's inlined and on-demand skills.
         */
        public Builder onDemandSkill(Skill skill) {
            onDemandSkills.add(Objects.requireNonNull(skill, "skill"));
            return this;
        }

        public Builder onDemandSkills(Skill... skills) {
            for (Skill skill : skills) {
                onDemandSkill(skill);
            }
            return this;
        }

        public Builder onDemandSkills(List<Skill> skills) {
            skills.forEach(this::onDemandSkill);
            return this;
        }

        public Builder tool(RegisteredTool tool) {
            tools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        public Builder tools(List<RegisteredTool> tools) {
            tools.forEach(this::tool);
            return this;
        }

        /** Lets this agent hand tasks to {@code agent} via {@code delegate_to_agent}, run on the same runtime. */
        public Builder delegateTo(Agent agent) {
            delegateAgents.add(Objects.requireNonNull(agent, "agent"));
            return this;
        }

        /** Lets this agent hand tasks to something else entirely, e.g. a remote agent service. */
        public Builder delegateTo(AgentDelegate delegate) {
            delegates.add(Objects.requireNonNull(delegate, "delegate"));
            return this;
        }

        public Agent build() {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("An agent needs a name matching " + NAME
                        + " (front matter 'name:' or Builder.name), was: " + name);
            }
            if (instructions.isBlank()) {
                throw new IllegalArgumentException("Agent '" + name + "' has no instructions: the Markdown body is empty");
            }
            if (strictFrontMatter && !metadata.isEmpty()) {
                throw new IllegalArgumentException("Unknown front matter key '" + metadata.keySet().iterator().next()
                        + "'; supported: " + String.join(", ", KNOWN_KEYS));
            }
            Set<String> skillNames = new HashSet<>();
            for (Skill skill : skills) {
                skillNames.add(skill.name().toLowerCase(Locale.ROOT));
            }
            for (Skill skill : onDemandSkills) {
                if (!skillNames.add(skill.name().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Agent '" + name + "' has more than one skill called '" + skill.name()
                            + "'; on-demand skills are loaded by name, so names must be unique");
                }
            }
            return new Agent(this);
        }
    }

    record Parsed(Map<String, String> frontMatter, String body) {
    }

    /** Splits off a leading {@code ---} front matter block of {@code key: value} lines ({@code #} starts a comment). */
    static Parsed parse(String markdown) {
        String text = markdown.startsWith("﻿") ? markdown.substring(1) : markdown;
        List<String> lines = text.lines().toList();
        int first = 0;
        while (first < lines.size() && lines.get(first).isBlank()) {
            first++;
        }
        if (first >= lines.size() || !lines.get(first).strip().equals("---")) {
            return new Parsed(Map.of(), text.strip());
        }
        Map<String, String> front = new LinkedHashMap<>();
        for (int i = first + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().equals("---")) {
                String body = String.join("\n", lines.subList(i + 1, lines.size())).strip();
                return new Parsed(front, body);
            }
            String content = stripComment(line).strip();
            if (content.isEmpty()) {
                continue;
            }
            int colon = content.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("Front matter line " + (i + 1) + " isn't 'key: value': " + line);
            }
            String key = content.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            if (front.put(key, unquote(content.substring(colon + 1).strip())) != null) {
                throw new IllegalArgumentException("Front matter key '" + key + "' appears twice");
            }
        }
        throw new IllegalArgumentException("Front matter starting with '---' is never closed by another '---' line");
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '"' || c == '\'') && (!quoted || c == quote)) {
                quoted = !quoted;
                quote = c;
            } else if (c == '#' && !quoted && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** {@code "anthropic/claude-sonnet-5-5, openai"} — provider, optionally {@code /model}, comma-separated, in preference order. */
    private static List<RouteEntry> parseModels(String value) {
        List<RouteEntry> route = new ArrayList<>();
        for (String part : value.split(",")) {
            String entry = part.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int slash = entry.indexOf('/');
            String providerName = (slash < 0 ? entry : entry.substring(0, slash)).strip();
            Provider provider = null;
            for (Provider candidate : Provider.values()) {
                if (candidate.name().equalsIgnoreCase(providerName) || candidate.toString().equalsIgnoreCase(providerName)) {
                    provider = candidate;
                }
            }
            if (provider == null) {
                List<String> known = new ArrayList<>();
                for (Provider candidate : Provider.values()) {
                    known.add(candidate.name().toLowerCase(Locale.ROOT));
                }
                throw new IllegalArgumentException("Unknown provider '" + providerName + "' in models; known: " + known);
            }
            String model = slash < 0 ? "" : entry.substring(slash + 1).strip();
            route.add(model.isEmpty() ? RouteEntry.of(provider) : RouteEntry.of(provider, model));
        }
        if (route.isEmpty()) {
            throw new IllegalArgumentException("models must list at least one provider or provider/model");
        }
        return List.copyOf(route);
    }

    private static ThinkingLevel parseThinkingLevel(String value) {
        try {
            return ThinkingLevel.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("thinking_level must be one of low, medium, high, max; was \"" + value + "\"");
        }
    }

    private static boolean parseBoolean(String key, String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("yes")) {
            return true;
        }
        if (v.equals("false") || v.equals("no")) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false; was \"" + value + "\"");
    }

    private static PlanMode parsePlanMode(String value) {
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.equals("RECURSIVE")) {
            return PlanMode.RECURSIVE_ON_EACH_STEP;
        }
        try {
            return PlanMode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("plan_mode must be one of auto, always_plan, never_plan, "
                    + "recursive_on_each_step; was \"" + value + "\"");
        }
    }
}
