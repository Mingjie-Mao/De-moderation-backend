package com.campusguard.moderation.engine.ai;

import com.campusguard.moderation.engine.ModerationEngine;
import com.campusguard.moderation.engine.ModerationEngineBundle;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires one model-backed engine per configured model, and only when a model is
 * configured at all.
 *
 * <p>Conditional on the same property that decides whether Spring AI builds a
 * client, so the two cannot disagree. With it unset there is no Gemini engine,
 * the registry finds only term matching, and the service runs exactly as it did
 * before this phase. A missing key is an ordinary configuration, not an error.
 *
 * <p>Registering several models is what turns "which one should this use" into a
 * question the evaluation harness answers on one dataset with one body of code,
 * rather than one settled by reading a vendor's comparison table.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "google-genai")
public class AiEngineConfig {

    @Bean
    public ModerationEngineBundle geminiEngines(
            ChatModel chatModel,
            AiProperties properties,
            ModerationPromptV1 prompt,
            VerdictParser parser,
            AiInvocationRecorder recorder) {

        List<ModerationEngine> engines = new ArrayList<>();

        for (String configured : properties.models()) {
            String model = configured.trim();
            if (model.isEmpty()) {
                continue;
            }

            // Each model gets its own resilience policy, so one being throttled or
            // unavailable does not open the circuit on another. Sharing one would
            // make a comparison run meaningless the moment either model wobbled.
            ChatCompletionPort port =
                    new ResilientChatCompletion(new SpringAiChatCompletion(chatModel, model), properties);

            engines.add(new GeminiModerationEngine(port, prompt, parser, recorder, properties));
        }

        return new ModerationEngineBundle(engines);
    }
}
