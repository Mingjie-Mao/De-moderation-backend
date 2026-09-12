package com.campusguard.evaluation.investigation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the investigation set.
 *
 * <p>Bundled rather than external, like the engine's starter samples, so the
 * benchmark runs on a fresh checkout with nothing to fetch. The sixteen are a
 * starter, chosen to cover the shapes an investigation meets rather than to be a
 * representative sample of a real queue.
 */
@Component
public class InvestigationScenarios {

    public static final String BUNDLED_PATH = "evaluation/investigation-scenarios.json";

    private final ObjectMapper objectMapper;

    public InvestigationScenarios(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<InvestigationScenario> load() throws IOException {
        try (InputStream stream = new ClassPathResource(BUNDLED_PATH).getInputStream()) {
            return read(stream);
        }
    }

    private List<InvestigationScenario> read(InputStream stream) throws IOException {
        List<InvestigationScenario> scenarios =
                objectMapper.readValue(stream, new TypeReference<List<InvestigationScenario>>() {});

        if (scenarios.isEmpty()) {
            throw new IllegalStateException("The investigation set is empty.");
        }

        return List.copyOf(scenarios);
    }
}
