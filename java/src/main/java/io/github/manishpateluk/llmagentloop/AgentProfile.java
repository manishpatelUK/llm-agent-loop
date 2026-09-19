package io.github.manishpateluk.llmagentloop;

/**
 * Describes an agent's overall behavior — goals, constraints, and other fixed attributes — so it
 * can be serialized into the system instructions for every LLM call a {@link AgentLoop} run
 * makes, similar in spirit to Claude "skills". Intentionally left as a marker for now; its
 * concrete shape (and how it json-ifies into system instructions) is designed in a later pass.
 */
public interface AgentProfile {
}
