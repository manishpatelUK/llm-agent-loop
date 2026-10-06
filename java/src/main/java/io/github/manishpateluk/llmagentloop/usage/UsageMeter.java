package io.github.manishpateluk.llmagentloop.usage;

/**
 * Receives a {@link UsageRecord} for every model call agents make — working steps, planning,
 * answer formatting, history compression and conversation compaction alike — for billing,
 * quotas and cost dashboards. Set with {@code AgentLoop.builder().usageMeter(...)}.
 *
 * <p>Metering is on by default, with an {@link InMemoryUsageMeter} that keeps running totals per
 * tenant and per user. Pass your own to write records to a database or billing system (a lambda
 * works), or {@link #NONE} to switch metering off. Called on the run's thread right after each
 * call completes; keep it quick, or hand off to a queue. Must be safe for concurrent runs.
 * An exception thrown here ends the run, so a meter that must never interfere should catch its own.
 */
@FunctionalInterface
public interface UsageMeter {

    /** Meters nothing: switches metering off. */
    UsageMeter NONE = record -> { };

    void record(UsageRecord record);
}
