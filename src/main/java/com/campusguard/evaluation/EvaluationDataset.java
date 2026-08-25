package com.campusguard.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads hand-labelled samples.
 *
 * <p>Reads from a file path when one is given and from the bundled starter set
 * otherwise. The real dataset is not in the repository: it has to be sampled from
 * genuine posts and labelled by a person, and a set written by the same kind of
 * system being measured would only prove that the system agrees with itself.
 */
@Component
public class EvaluationDataset {

    public static final String BUNDLED_PATH = "evaluation/starter-samples.json";

    private final ObjectMapper objectMapper;

    public EvaluationDataset(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Loaded load(String path) throws IOException {
        if (path == null || path.isBlank()) {
            try (InputStream in = new ClassPathResource(BUNDLED_PATH).getInputStream()) {
                return new Loaded(BUNDLED_PATH, read(in), true);
            }
        }

        Path file = Path.of(path);
        if (!Files.exists(file)) {
            throw new IOException("No dataset at " + file.toAbsolutePath());
        }
        try (InputStream in = Files.newInputStream(file)) {
            return new Loaded(file.getFileName().toString(), read(in), false);
        }
    }

    private List<LabelledSample> read(InputStream in) throws IOException {
        return objectMapper.readValue(in, new TypeReference<List<LabelledSample>>() {});
    }

    /**
     * @param starter true when this is the bundled smoke-test set rather than a
     *     real labelled corpus. Carried all the way into the report so that no
     *     number produced from twenty-odd invented samples can be mistaken for a
     *     measurement.
     */
    public record Loaded(String name, List<LabelledSample> samples, boolean starter) {
    }
}
