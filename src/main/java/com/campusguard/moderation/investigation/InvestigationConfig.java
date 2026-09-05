package com.campusguard.moderation.investigation;

import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.admin.AdminModerationService;
import com.campusguard.moderation.engine.ai.AiInvocationRecorder;
import com.campusguard.moderation.engine.ai.ModelPolicies;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the investigation assistant, and only when it has been asked for.
 *
 * <p>Two conditions, and both are meant. The outer one is the same property that
 * decides whether Spring AI builds a client at all, so this cannot be the thing
 * that turns a missing key into a failed startup. The inner one is the
 * assistant's own switch, because it is not free: one investigation is several
 * model calls where a verdict is one, and something that costs that much should
 * be turned on deliberately rather than by having the dependency present.
 *
 * <p>With either off there is no {@link CaseInvestigator} bean and the service
 * runs exactly as it did before this work — which is the property the whole
 * design has been arranged around.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "google-genai")
public class InvestigationConfig {

    private static final Logger log = LoggerFactory.getLogger(InvestigationConfig.class);

    /**
     * The assistant uses the model live traffic uses.
     *
     * <p>Not a default anyone should override lightly: the shared circuit only
     * protects the queue if the assistant is calling the same endpoint. Pointed
     * at a second model it would be outside the breaker that matters, and would
     * be the largest single source of calls to a provider already known to be
     * failing.
     */
    @Bean
    @ConditionalOnProperty(name = "campusguard.moderation.investigator.enabled", havingValue = "true")
    public CaseInvestigator caseInvestigator(
            ChatModel chatModel,
            ModelPolicies policies,
            ToolRegistry tools,
            InvestigationPrompt prompt,
            BriefParser parser,
            AiInvocationRecorder recorder,
            AdminModerationService cases,
            ModerationCaseRepository caseRepository,
            InvestigatorProperties properties,
            ObjectMapper objectMapper) {

        String model = policies.primaryModel();
        log.info("Case investigation is enabled, using {} and prompt {}.", model, prompt.version());

        ToolCallingPort port = new ResilientToolCalling(
                new SpringAiToolCompletion(chatModel, model, objectMapper), policies.forModel(model));

        return new CaseInvestigator(
                port, tools, prompt, parser, recorder, cases, caseRepository, properties);
    }
}
