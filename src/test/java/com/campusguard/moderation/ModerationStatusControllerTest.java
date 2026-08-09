package com.campusguard.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.moderation.engine.EngineRegistry;
import com.campusguard.moderation.engine.ModerationEngine;
import com.campusguard.moderation.engine.ModerationRequest;
import com.campusguard.moderation.engine.ModerationVerdict;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModerationStatusControllerTest {

    @Test
    void reportsTheConfiguredModelWhenItIsTheActiveEngine() {
        ModerationProperties properties = properties("gemini-demo/v2");
        EngineRegistry registry = new EngineRegistry(
                List.of(engine("keyword-v1", false), engine("gemini-demo/v2", true)),
                List.of(),
                properties);

        ModerationStatusResponse response = new ModerationStatusController(registry, properties).get();

        assertThat(response.configuredEngine()).isEqualTo("gemini-demo/v2");
        assertThat(response.activeEngine()).isEqualTo("gemini-demo/v2");
        assertThat(response.configuredEngineAvailable()).isTrue();
        assertThat(response.llmActive()).isTrue();
    }

    @Test
    void tellsTheClientWhenAConfiguredModelHasFallenBackToRules() {
        ModerationProperties properties = properties("missing-model/v2");
        EngineRegistry registry =
                new EngineRegistry(List.of(engine("keyword-v1", false)), List.of(), properties);

        ModerationStatusResponse response = new ModerationStatusController(registry, properties).get();

        assertThat(response.activeEngine()).isEqualTo("keyword-v1");
        assertThat(response.configuredEngineAvailable()).isFalse();
        assertThat(response.llmActive()).isFalse();
    }

    private ModerationProperties properties(String engine) {
        return new ModerationProperties(
                engine, "keyword-v1", 20, Duration.ofSeconds(2), Duration.ofMinutes(5));
    }

    private ModerationEngine engine(String name, boolean languageModel) {
        return new ModerationEngine() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ModerationVerdict evaluate(ModerationRequest request) {
                return ModerationVerdict.allow(1.0, "test");
            }

            @Override
            public boolean isLanguageModel() {
                return languageModel;
            }
        };
    }
}
