package com.campusguard.moderation.engine.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * How vendor failures are translated.
 *
 * <p>Both cases here were found by making real calls rather than by imagining
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
