package com.campusguard.moderation.investigation;

import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.admin.AdminModerationService;
import com.campusguard.moderation.engine.ai.AiInvocationRecorder;
import com.campusguard.moderation.engine.ai.ModelPolicies;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
    /**
     * The configured wording, chosen the way {@code MODERATION_ENGINE} chooses an
     * engine.
     *
     * <p>Every version stays registered rather than being deleted when the next
     * one lands, so switching back is a property and two versions can be run over
     * the same cases and compared. Unlike the engine registry this refuses to
     * start on an unknown name: an engine falling back to term matching still
     * moderates, whereas an assistant silently running last month's wording would
     * produce briefs attributed to a version that did not write them.
     */
    private InvestigationPrompt select(List<InvestigationPrompt> prompts, String version) {
        return prompts.stream()
                .filter(prompt -> prompt.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No investigation prompt with version '%s'. Available: %s."
                                .formatted(version, prompts.stream().map(InvestigationPrompt::version).sorted().toList())));
    }

    @Bean
    @ConditionalOnProperty(name = "campusguard.moderation.investigator.enabled", havingValue = "true")
    public Investigator caseInvestigator(
            ChatModel chatModel,
            ModelPolicies policies,
            ToolRegistry tools,
            List<InvestigationPrompt> prompts,
            BriefParser parser,
            AiInvocationRecorder recorder,
            AdminModerationService cases,
            ModerationCaseRepository caseRepository,
            InvestigatorProperties properties,
            ObjectMapper objectMapper) {

        InvestigationPrompt prompt = select(prompts, properties.promptVersion());
        String model = policies.primaryModel();
        log.info("Case investigation is enabled, using {}, prompt {} and {} run(s) per case.",
                model, prompt.version(), properties.runs());

        ToolCallingPort port = new ResilientToolCalling(
                new SpringAiToolCompletion(chatModel, model, objectMapper), policies.forModel(model));

        CaseInvestigator single = new CaseInvestigator(
                port, tools, prompt, parser, recorder, cases, caseRepository, properties);

        // One run is the plain loop, not a consensus of one. A wrapper that voted
        // among a single answer would report every case as SETTLED, which is the
        // self-graded certainty this was meant to replace.
        return properties.runs() == 1 ? single : new ConsensusInvestigator(single, properties.runs());
    }
}
