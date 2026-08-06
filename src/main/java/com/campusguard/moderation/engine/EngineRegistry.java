package com.campusguard.moderation.engine;

import com.campusguard.moderation.ModerationProperties;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Every engine on the classpath, addressable by name.
 *
 * <p>This is what makes the seam pay for itself. Which engine judges live traffic
 * is a configuration value, so adding a model-backed implementation is a new
 * class and a changed property rather than an edit to the worker. And because
 * any engine can be fetched by name, the evaluation harness can score two of
 * them against the same data with the same code, which is the only way a claim
 * like "the model is better than the rules" means anything.
 */
@Component
public class EngineRegistry {

    private final Map<String, ModerationEngine> byName;
    private final String primaryName;

    public EngineRegistry(List<ModerationEngine> engines, ModerationProperties properties) {
        this.byName = engines.stream().collect(Collectors.toMap(ModerationEngine::name, Function.identity()));
        this.primaryName = properties.engine();

        // Failing at startup rather than on the first case: a typo in the engine
        // name should not surface as moderation silently never running.
        if (!byName.containsKey(primaryName)) {
            throw new IllegalStateException(
                    "Configured engine '" + primaryName + "' is not registered. Available: " + byName.keySet());
        }
    }

    public ModerationEngine primary() {
        return byName.get(primaryName);
    }

    public ModerationEngine require(String name) {
        ModerationEngine engine = byName.get(name);
        if (engine == null) {
            throw new IllegalArgumentException(
                    "No engine named '" + name + "'. Available: " + byName.keySet());
        }
        return engine;
    }

    public List<String> names() {
        return byName.keySet().stream().sorted().toList();
    }
}
