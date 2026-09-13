package com.campusguard.moderation.engine;

import com.campusguard.moderation.ModerationProperties;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(EngineRegistry.class);

    private final Map<String, ModerationEngine> byName;
    private final String configuredPrimary;
    private final String fallbackName;

    /**
     * @param engines the ones declared a class at a time, which Spring collects
     * @param bundles the ones whose number is a configuration value, such as a
     *     model per entry in a list, which Spring cannot
     */
    public EngineRegistry(
            List<ModerationEngine> engines,
            List<ModerationEngineBundle> bundles,
            ModerationProperties properties) {

        List<ModerationEngine> all = new java.util.ArrayList<>(engines);
        bundles.forEach(bundle -> all.addAll(bundle.engines()));

        this.byName = all.stream()
                .collect(Collectors.toMap(ModerationEngine::name, Function.identity(), (first, second) -> first));
        this.configuredPrimary = properties.engine();
        this.fallbackName = properties.fallbackEngine();

        // The fallback is the one thing that must exist. If it is missing there is
        // nothing to degrade to, and the failure should be at startup rather than
        // during the first outage.
        if (!byName.containsKey(fallbackName)) {
            throw new IllegalStateException(
                    "Fallback engine '" + fallbackName + "' is not registered. Available: " + byName.keySet());
        }

        if (!byName.containsKey(configuredPrimary)) {
            // Deliberately not fatal. The usual reason for a configured engine to
            // be absent is that its credentials are not set, and refusing to start
            // would turn a missing API key into a total outage — the precise
            // failure this phase exists to rule out. Loud, and still moderating.
            log.error(
                    "Configured engine '{}' is not registered; falling back to '{}'. Available: {}",
                    configuredPrimary, fallbackName, byName.keySet());
        } else {
            // Said on the way up, and not only when it goes wrong.
            //
            // Which engine judges live traffic is a deployment decision with a
            // silent failure mode: MODERATION_ENGINE that never reached the
            // process leaves the queue running on term matching, every case still
            // gets a verdict, and nothing anywhere says the model was not asked.
            // The same argument as MediaConfig's startup line, which the runbook
            // already tells an operator to read on every release, and there was
            // no equivalent for the thing that actually decides the cases.
            log.info(
                    "Moderation engine is {}, degrading to {}. Registered: {}",
                    configuredPrimary, fallbackName, byName.keySet());
        }
    }

    /** The engine that judges live traffic, or the fallback when it is unavailable. */
    public ModerationEngine primary() {
        return byName.getOrDefault(configuredPrimary, byName.get(fallbackName));
    }

    /** What the queue degrades to. Never a model, so it cannot fail the same way. */
    public ModerationEngine fallback() {
        return byName.get(fallbackName);
    }

    public boolean primaryIsAvailable() {
        return byName.containsKey(configuredPrimary);
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
