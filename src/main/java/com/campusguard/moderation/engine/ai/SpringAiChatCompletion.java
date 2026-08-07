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
            String detail = describe(ex);
            throw new ModelCallException(classify(detail), detail, ex);
        }
    }

    /**
     * Separates being throttled from being broken.
     *
     * <p>Matched on the message rather than an exception type because the Google
     * SDK reports every HTTP status through the same {@code ClientException}, so
     * the status code is only available as text. Fragile in principle; the
     * alternative is treating a 429 as permanent, which turns a provider saying
     * "not yet" into a fallback to the rule engine for every case in the batch.
     */
    private InvocationStatus classify(String detail) {
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        boolean throttled = lower.contains("429")
                || lower.contains("resource_exhausted")
                || lower.contains("quota")
                || lower.contains("rate limit");

        return throttled ? InvocationStatus.RATE_LIMITED : InvocationStatus.ERROR;
    }

    /**
     * Flattens the cause chain into the message.
     *
     * <p>Taking only the top-level message lost every useful detail on the first
     * real call this project ever made: the operator saw "Failed to generate
     * content" while the cause underneath said the model had been retired, and on
     * the next attempt that the project had no quota at all. Those are three
     * different problems with three different fixes, and the wrapper was hiding
     * all of them behind one sentence that named none.
     */
    private String describe(Throwable failure) {
        StringBuilder message = new StringBuilder();
        Throwable current = failure;
        int depth = 0;

        while (current != null && depth < 5) {
            String text = current.getMessage();
            if (text != null && !text.isBlank() && message.indexOf(text) < 0) {
                if (!message.isEmpty()) {
                    message.append(" | ");
                }
                message.append(current.getClass().getSimpleName()).append(": ").append(text.strip());
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }

        return message.isEmpty() ? failure.getClass().getSimpleName() : message.toString();
    }

    private Integer asInt(Number value) {
        return value == null ? null : value.intValue();
    }
}
