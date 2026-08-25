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
 * Wires one model-backed engine per model-and-prompt pair, and only when a model
 * is configured at all.
 *
 * <p>Conditional on the same property that decides whether Spring AI builds a
 * client, so the two cannot disagree. With it unset there is no Gemini engine,
 * the registry finds only term matching, and the service runs exactly as it did
 * before this phase. A missing key is an ordinary configuration, not an error.
 *
 * <p>Both axes are registered because both change the answer. Two models are two
 * engines the harness scores side by side; two prompt versions are two more. That
 * turns "the newer wording is better" and "the larger model is worth it" into
 * questions answered on one dataset with one body of code, rather than settled by
 * reading a vendor's table or by trusting whoever last edited the prompt.
 *
 * <p>The cross product also means a prompt version is never quietly retired: the
 * old wording keeps running next to the new one until the numbers say to drop it.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "google-genai")
public class AiEngineConfig {

    @Bean
    public ModerationEngineBundle geminiEngines(
            ChatModel chatModel,
            AiProperties properties,
            List<ModerationPrompt> prompts,
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
            //
            // The prompts share it, though: they are the same endpoint under the
            // same quota, and pretending otherwise would let a throttled account
            // look like one healthy prompt and one broken one.
            ChatCompletionPort port =
                    new ResilientChatCompletion(new SpringAiChatCompletion(chatModel, model), properties);

            prompts.forEach(prompt ->
                    engines.add(new GeminiModerationEngine(port, prompt, parser, recorder, properties)));
        }

        return new ModerationEngineBundle(engines);
    }
}
