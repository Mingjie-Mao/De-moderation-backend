package com.campusguard.moderation.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * How vendor answers are translated: failures into statuses, usage into token
 * counts.
 *
 * <p>The failure cases were found by making real calls rather than by imagining
 * them: a retired model answered 404 and a project without quota answered 429,
 * and the layer reported both as the same anonymous failure.
 */
class SpringAiChatCompletionTest {

    @Test
    void reportsThrottlingSeparatelyFromOtherFailures() {
        SpringAiChatCompletion completion = completionThatFails(
                new RuntimeException("Failed to generate content",
                        new IllegalStateException("429 . You exceeded your current quota")));

        assertThatThrownBy(() -> completion.complete("s", "u"))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.RATE_LIMITED);
    }

    @Test
    void treatsAnythingElseAsAPlainError() {
        SpringAiChatCompletion completion = completionThatFails(
                new RuntimeException("Failed to generate content",
                        new IllegalStateException("404 . This model is no longer available to new users")));

        assertThatThrownBy(() -> completion.complete("s", "u"))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).status())
                .isEqualTo(InvocationStatus.ERROR);
    }

    /**
     * The vendor's own message named nothing. Everything useful was one level
     * down, which is why the whole chain reaches the log and the invocation record.
     */
    @Test
    void carriesTheRootCauseIntoTheMessage() {
        SpringAiChatCompletion completion = completionThatFails(
                new RuntimeException("Failed to generate content",
                        new IllegalStateException("404 . This model models/gemini-2.5-flash is no longer available")));

        assertThatThrownBy(() -> completion.complete("s", "u"))
                .isInstanceOf(ModelCallException.class)
                .hasMessageContaining("Failed to generate content")
                .hasMessageContaining("gemini-2.5-flash")
                .hasMessageContaining("no longer available");
    }

    @Test
    void reportsTheConfiguredModelName() {
        assertThat(completionThatFails(new RuntimeException("boom")).modelName()).isEqualTo("test-model");
    }

    @Test
    void reportsTheTokensTheProviderCounted() {
        ChatResponse counted = new ChatResponse(
                List.of(new Generation(AssistantMessage.builder().content("{}").build())),
                new ChatResponseMetadata.Builder().usage(new DefaultUsage(622, 48)).build());

        ChatCompletionPort.CompletionResult result = completionAnswering(counted).complete("s", "u");

        assertThat(result.promptTokens()).isEqualTo(622);
        assertThat(result.completionTokens()).isEqualTo(48);
    }

    /**
     * Unknown, not zero. Spring AI stands in for a missing usage with an
     * {@code EmptyUsage} that answers 0, and a zero is summed into the token
     * figures as a call that cost nothing, where a null is left out of them.
     */
    @Test
    void recordsUsageAsUnknownWhenTheProviderReportedNone() {
        ChatResponse uncounted =
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("{}").build())));

        ChatCompletionPort.CompletionResult result = completionAnswering(uncounted).complete("s", "u");

        assertThat(result.promptTokens()).isNull();
        assertThat(result.completionTokens()).isNull();
    }

    private SpringAiChatCompletion completionAnswering(ChatResponse response) {
        ChatModel answering = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return response;
            }
        };
        return new SpringAiChatCompletion(answering, "test-model");
    }

    private SpringAiChatCompletion completionThatFails(RuntimeException failure) {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw failure;
            }
        };
        return new SpringAiChatCompletion(failing, "test-model");
    }
}
