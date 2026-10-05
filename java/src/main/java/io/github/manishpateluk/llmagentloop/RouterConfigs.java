package io.github.manishpateluk.llmagentloop;

import com.manishpateluk.llmrouter.config.Feature;
import com.manishpateluk.llmrouter.config.RouterConfig;

import java.util.EnumSet;
import java.util.Set;

/**
 * Adjusts a caller's {@link RouterConfig} for one of the loop's own calls without losing any of
 * the caller's choices (route, thinking level, temperature...). {@code RouterConfig} has no
 * {@code toBuilder()}, so every field is copied here explicitly — keep this in step with it.
 */
final class RouterConfigs {

    private RouterConfigs() {
    }

    /** {@code base} (or the default config) with {@code feature} added to its required features. */
    static RouterConfig requiring(RouterConfig base, Feature feature) {
        RouterConfig b = base != null ? base : RouterConfig.builder().build();
        Set<Feature> required = b.getRequiredFeatures().isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(b.getRequiredFeatures());
        required.add(feature);
        return copy(b).requiredFeatures(Set.copyOf(required)).build();
    }

    /** {@code base} (or the default config) with cost-optimized ordering on — for the loop's cheap housekeeping calls. */
    static RouterConfig costOptimized(RouterConfig base) {
        RouterConfig b = base != null ? base : RouterConfig.builder().build();
        return copy(b).costOptimized(true).build();
    }

    private static RouterConfig.RouterConfigBuilder copy(RouterConfig b) {
        return RouterConfig.builder()
                .route(b.getRoute())
                .thinkingLevel(b.getThinkingLevel())
                .costOptimized(b.isCostOptimized())
                .structuredOutputStrategy(b.getStructuredOutputStrategy())
                .temperature(b.getTemperature())
                .topP(b.getTopP())
                .requiredFeatures(b.getRequiredFeatures());
    }
}
