package com.campusguard.moderation.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.admin.AdminModerationService;
import com.campusguard.moderation.engine.ai.AiInvocationRecorder;
import com.campusguard.moderation.engine.ai.AiProperties;
import com.campusguard.moderation.engine.ai.ModelPolicies;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Off unless asked for, and asked for in two places.
 *
 * <p>"Disabled by default" is the sort of claim that is true when written and
 * quietly false two commits later, because nothing fails when a condition is
 * dropped — the bean simply starts existing. These assertions are the only thing
 * that would notice.
 *
 * <p>Runs the configuration on its own against stubs rather than starting the
 * application, since what is under test is the conditions and the wiring, not
 * anything the collaborators do.
 */
class InvestigationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubCollaborators.class, InvestigationConfig.class);

    /**
     * The outer condition: with no model provider configured there is no
     * assistant, and — more to the point — nothing here can turn a missing API key
     * into a failed startup.
     */
    @Test
    void isAbsentWhenNoModelProviderIsConfigured() {
        runner.withPropertyValues("campusguard.moderation.investigator.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CaseInvestigator.class));
    }

    /**
     * The inner condition. A provider being configured means verdicts use a model;
     * it does not mean reviewers want to spend several calls per click on top.
     */
    @Test
    void isAbsentWhenTheProviderIsConfiguredButTheAssistantIsNot() {
        runner.withPropertyValues("spring.ai.model.chat=google-genai")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CaseInvestigator.class));
    }

    /**
     * An unknown prompt version stops startup rather than quietly running another
     * one. A brief attributed to wording that did not write it is worse than no
     * brief, because the attribution is what makes a later comparison mean
     * anything.
     */
    @Test
    void refusesToStartOnAPromptVersionThatDoesNotExist() {
        runner.withPropertyValues(
                        "spring.ai.model.chat=google-genai",
                        "campusguard.moderation.investigator.enabled=true")
                .withAllowBeanDefinitionOverriding(true)
                .withBean(
                        "investigatorProperties",
                        InvestigatorProperties.class,
                        () -> new InvestigatorProperties(true, 5, 1, "inv-v9", 1))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("inv-v9")
                        .hasStackTraceContaining("inv-v1"));
    }

    @Test
    void isBuiltWhenBothAreSwitchedOn() {
        runner.withPropertyValues(
                        "spring.ai.model.chat=google-genai",
                        "campusguard.moderation.investigator.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(CaseInvestigator.class));
    }

    @Configuration
    static class StubCollaborators {

        /** The first configured model is the one the assistant must use; see InvestigationConfig. */
        static final AiProperties AI = new AiProperties(
                Duration.ofSeconds(5), 50, Duration.ofSeconds(30), 10, 2, 2, Duration.ofMillis(1),
                List.of("primary-model", "second-model"));

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        ModelPolicies modelPolicies() {
            return new ModelPolicies(AI);
        }

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry(List.of(), new ObjectMapper());
        }

        @Bean
        InvestigationPromptV1 promptV1() {
            return new InvestigationPromptV1();
        }

        @Bean
        InvestigationPrompt promptV2(InvestigationPromptV1 v1) {
            return new InvestigationPromptV2(v1);
        }

        @Bean
        BriefParser briefParser() {
            return new BriefParser(new ObjectMapper());
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AiInvocationRecorder recorder() {
            return mock(AiInvocationRecorder.class);
        }

        @Bean
        AdminModerationService adminModerationService() {
            return mock(AdminModerationService.class);
        }

        @Bean
        ModerationCaseRepository moderationCaseRepository() {
            return mock(ModerationCaseRepository.class);
        }

        @Bean
        InvestigatorProperties investigatorProperties() {
            return new InvestigatorProperties(true, 5, 1, InvestigationPromptV2.VERSION, 1);
        }
    }
}
