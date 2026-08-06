package com.campusguard.moderation.engine.ai;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The one place this project touches a vendor's model API.
 *
 * <p>Everything above it works in terms of {@link ChatCompletionPort}, so
 * replacing the provider is a second implementation of this one class. That is
 * not a hypothetical benefit here: it is also what makes the resilience layer
 * testable, since a stub implementing the same three methods can hang, throw or
 * return nonsense without a network or an API key.
 */
public class SpringAiChatCompletion implements ChatCompletionPort {

    private final ChatModel chatModel;
    private final String modelName;

    public SpringAiChatCompletion(ChatModel chatModel, String modelName) {
        this.chatModel = chatModel;
        this.modelName = modelName;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public CompletionResult complete(String systemPrompt, String userPrompt) {
        try {
            ChatResponse response = chatModel.call(
                    new Prompt(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));

            if (response == null || response.getResult() == null) {
                throw new ModelCallException(InvocationStatus.ERROR, "The provider returned no result.");
            }

            String text = response.getResult().getOutput().getText();

            Integer promptTokens = null;
            Integer completionTokens = null;
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                promptTokens = asInt(response.getMetadata().getUsage().getPromptTokens());
                completionTokens = asInt(response.getMetadata().getUsage().getCompletionTokens());
            }

            return new CompletionResult(text, promptTokens, completionTokens);

        } catch (ModelCallException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Refused credentials, rate limiting and transport failures all arrive
            // as vendor-specific exceptions. They are flattened here so that
            // nothing above this class has to know a second SDK's type hierarchy.
            throw new ModelCallException(InvocationStatus.ERROR, String.valueOf(ex.getMessage()), ex);
        }
    }

    private Integer asInt(Number value) {
        return value == null ? null : value.intValue();
    }
}
