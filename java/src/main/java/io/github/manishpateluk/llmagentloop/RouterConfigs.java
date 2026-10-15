package io.github.manishpateluk.llmagentloop;

import io.github.manishpateluk.llmrouter.config.Feature;
import io.github.manishpateluk.llmrouter.config.RouterConfig;

import java.util.EnumSet;
import java.util.Set;

/**
 * Adjusts a caller's {@link RouterConfig} for one of the loop's own calls without losing any of
 * the caller's choices (route, thinking level, fallback policy, temperature...): each adjustment
 * goes through {@code toBuilder()}, so fields added to {@code RouterConfig} later survive too.
 */
final class RouterConfigs {

    private RouterConfigs() {
    }

    /** {@code base} (or the default config) with {@code feature} added to its required features. */
    static RouterConfig requiring(RouterConfig base, Feature feature) {
        RouterConfig b = base != null ? base : RouterConfig.builder().build();
        Set<Feature> required = b.getRequiredFeatures().isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(b.getRequiredFeatures());
        required.add(feature);
        return b.toBuilder().requiredFeatures(Set.copyOf(required)).build();
    }

    /** {@code base} (or the default config) with cost-optimized ordering on — for the loop's cheap housekeeping calls. */
    static RouterConfig costOptimized(RouterConfig base) {
        RouterConfig b = base != null ? base : RouterConfig.builder().build();
        return b.toBuilder().costOptimized(true).build();
    }
}
