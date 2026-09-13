package com.campusguard.moderation.engine.ai;

import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import com.campusguard.moderation.engine.ModerationRequest;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

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
        return complete(systemPrompt, userPrompt, List.of());
    }

    @Override
    public CompletionResult complete(
            String systemPrompt,
            String userPrompt,
            List<ModerationRequest.MediaInput> attachments) {
        try {
            // The model is named per call rather than taken from the bean's
            // defaults, so several models can be registered as separate engines
            // and scored against each other on one dataset. Choosing between them
            // by reading spec sheets is guessing; this makes it a measurement.
            List<Media> media = attachments.stream()
                    .map(value -> new Media(
                            MimeTypeUtils.parseMimeType(value.contentType()),
                            new ByteArrayResource(value.bytes())))
                    .toList();
            UserMessage userMessage = media.isEmpty()
                    ? new UserMessage(userPrompt)
                    : UserMessage.builder().text(userPrompt).media(media).build();
            ChatResponse response = chatModel.call(new Prompt(
                    List.of(new SystemMessage(systemPrompt), userMessage),
                    GoogleGenAiChatOptions.builder().model(modelName).temperature(0.0).build()));

            if (response == null || response.getResult() == null) {
                throw new ModelCallException(InvocationStatus.ERROR, "The provider returned no result.");
            }

            String text = response.getResult().getOutput().getText();

            // An EmptyUsage is Spring AI's stand-in for no usage reported, and it
            // answers 0. The port's contract is null for unknown; a zero would be
            // summed into the token figures as a call that cost nothing.
            Integer promptTokens = null;
            Integer completionTokens = null;
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (usage != null && !(usage instanceof EmptyUsage)) {
                promptTokens = asInt(usage.getPromptTokens());
                completionTokens = asInt(usage.getCompletionTokens());
            }

            return new CompletionResult(text, promptTokens, completionTokens);

        } catch (ModelCallException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Refused credentials, rate limiting and transport failures all arrive
            // as vendor-specific exceptions. They are flattened by ProviderFailures
            // so that nothing above this class has to know a second SDK's type
            // hierarchy, and so the tool-calling adapter reaches the same verdict
            // about the same failure.
            throw ProviderFailures.asModelCallException(ex);
        }
    }

    private Integer asInt(Number value) {
        return value == null ? null : value.intValue();
    }
}
