package io.github.manishpateluk.llmagentloop.tool;

import java.util.Objects;

/**
 * An optional check on outside content before the model sees it — e.g. a classifier call that
 * flags likely prompt-injection attempts, or a filter that removes hidden text. Runs on the result
 * of every tool marked {@code untrustedOutput}, after any {@link ToolInterceptor#after}. Set with
 * {@code AgentLoop.builder().contentScreener(...)}; the default, {@link #NONE}, allows everything.
 * No screener catches everything, so treat this as one layer among several.
 */
@FunctionalInterface
public interface ContentScreener {

    /** Allows everything: no screening. */
    ContentScreener NONE = (content, source, context) -> Screening.allow();

    /**
     * @param content what the tool returned
     * @param source  the tool's name
     */
    Screening screen(String content, String source, ToolContext context);

    /**
     * @param action what to do with the content
     * @param text   for {@link Action#REPLACE}: the content to use instead; for {@link Action#WITHHOLD}: why
     */
    record Screening(Action action, String text) {

        public enum Action {
            /** Pass the content through unchanged. */
            ALLOW,
            /** Pass this replacement instead, e.g. with suspicious passages removed. */
            REPLACE,
            /** Don't show the content to the model at all; it's told the content was withheld and why. */
            WITHHOLD
        }

        public Screening {
            Objects.requireNonNull(action, "action");
        }

        public static Screening allow() {
            return new Screening(Action.ALLOW, null);
        }

        public static Screening replace(String content) {
            return new Screening(Action.REPLACE, Objects.requireNonNull(content, "content"));
        }

        public static Screening withhold(String reason) {
            return new Screening(Action.WITHHOLD, Objects.requireNonNull(reason, "reason"));
        }
    }
}
