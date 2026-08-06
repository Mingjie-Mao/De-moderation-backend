package com.campusguard.moderation.engine.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the model-backed engine, and only when a model is actually configured.
 *
 * <p>Conditional on the same property that decides whether Spring AI builds a
 * client at all, so the two cannot disagree. With it unset there is no Gemini
 * engine bean, the registry finds only term matching, and the service runs
 * exactly as it did before this phase. That is the intended posture: a missing
 * key is an ordinary configuration, not an error.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "google-genai")
public class AiEngineConfig {

    @Bean
    public ChatCompletionPort chatCompletionPort(
            ChatModel chatModel,
            AiProperties properties,
            @Value("${spring.ai.google.genai.chat.options.model:unknown}") String modelName) {

        // The resilience policy wraps the vendor call rather than living inside
        // it, which is what keeps the timeout and the breaker testable.
        return new ResilientChatCompletion(new SpringAiChatCompletion(chatModel, modelName), properties);
    }

    @Bean
    public GeminiModerationEngine geminiModerationEngine(
            ChatCompletionPort chatCompletionPort,
            ModerationPromptV1 prompt,
            VerdictParser parser,
            AiInvocationRecorder recorder,
            AiProperties properties) {
        return new GeminiModerationEngine(chatCompletionPort, prompt, parser, recorder, properties);
    }
}
