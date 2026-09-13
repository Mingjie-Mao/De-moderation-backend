package com.campusguard.moderation.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusguard.moderation.engine.ai.InvocationStatus;
import com.campusguard.moderation.engine.ai.ModelCallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * The one class between the investigation and the vendor, with the vendor
 * replaced by a recording.
 *
 * <p>Nothing else exercises it without a key. The loop is tested against a
 * scripted {@link ToolCallingPort}, which is this class's interface rather than
 * this class, and the two suites that call a real model are skipped wherever
 * {@code GEMINI_API_KEY} is unset — CI included. So what this adapter decides on
 * its own is pinned here: what goes out, how a turn is read back, and what a
 * failure becomes.
 */
class SpringAiToolCompletionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ToolSpec AUTHOR_HISTORY =
            new ToolSpec("authorHistory", "Decisions already made about this author.", ToolSpec.noArguments());

    private static final ToolSpec RULE_TEXT = new ToolSpec(
            "ruleText", "The wording of one rule.", ToolSpec.oneRequiredString("ruleCode", "A rule code, such as ABUSE."));

    /**
     * Tool execution stays with the loop.
     *
     * <p>The assertion that matters most in this class. Left at Spring AI's
     * default, the framework runs the tools itself and hands back only the final
     * text, and the whitelist, the step budget and the read-only transaction would
     * all be bypassed without any test above this one noticing.
     */
    @Test
    void asksForOneTurnAndLeavesRunningToolsToTheLoop() throws Exception {
        RecordingModel model = RecordingModel.answering(finished("{}"));

        completion(model).next(
                "system", List.of(new ToolCallingPort.Message.Prompt("A case.")), List.of(AUTHOR_HISTORY, RULE_TEXT));

        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) model.prompt().getOptions();
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(options.getModel()).isEqualTo("test-model");
        assertThat(options.getTemperature()).isZero();
        assertThat(options.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("authorHistory", "ruleText");
        assertThat(MAPPER.readTree(options.getToolCallbacks().get(1).getToolDefinition().inputSchema()))
                .isEqualTo(MAPPER.valueToTree(RULE_TEXT.parameters()));
    }

    /** If the framework ever does try, that should be loud rather than a lookup outside the budget. */
    @Test
    void refusesToRunAToolOnTheFrameworksBehalf() {
        RecordingModel model = RecordingModel.answering(finished("{}"));
        completion(model).next("system", List.of(), List.of(AUTHOR_HISTORY));

        ToolCallback advertised =
                ((GoogleGenAiChatOptions) model.prompt().getOptions()).getToolCallbacks().getFirst();

        assertThatThrownBy(() -> advertised.call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorHistory");
    }

    @Test
    void readsToolCallsBackAsARequestToLookThingsUp() {
        AssistantMessage asked = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "authorHistory", ""),
                        new AssistantMessage.ToolCall("call-2", "function", "ruleText", "{\"ruleCode\":\"ABUSE\"}")))
                .build();

        ToolCallingPort.Turn turn = completion(RecordingModel.answering(response(asked, null)))
                .next("system", List.of(), List.of(AUTHOR_HISTORY, RULE_TEXT))
                .turn();

        assertThat(turn).isInstanceOfSatisfying(ToolCallingPort.Turn.CallTools.class, request -> {
            assertThat(request.calls()).extracting(ToolCall::id).containsExactly("call-1", "call-2");
            assertThat(request.calls()).extracting(ToolCall::name).containsExactly("authorHistory", "ruleText");
            assertThat(request.calls().get(0).arguments()).isNull();
            assertThat(request.calls().get(1).arguments().path("ruleCode").asText()).isEqualTo("ABUSE");
        });
    }

    /**
     * Arguments that are not JSON are the model's mistake, and the registry is
     * where a mistake becomes an answer. Throwing here would end the
     * investigation over the most ordinary error a model makes.
     */
    @Test
    void passesArgumentsThatAreNotJsonOnAsNoArgumentsAtAll() {
        AssistantMessage asked = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "ruleText", "ruleCode=ABUSE")))
                .build();

        ToolCallingPort.Turn turn = completion(RecordingModel.answering(response(asked, null)))
                .next("system", List.of(), List.of(RULE_TEXT))
                .turn();

        assertThat(turn).isInstanceOfSatisfying(ToolCallingPort.Turn.CallTools.class, request ->
                assertThat(request.calls()).singleElement().satisfies(call -> {
                    assertThat(call.name()).isEqualTo("ruleText");
                    assertThat(call.arguments()).isNull();
                }));
    }

    @Test
    void readsATurnWithNoToolCallsAsTheFinishedBrief() {
        ToolCallingPort.Turn turn = completion(RecordingModel.answering(finished("{\"summary\":\"...\"}")))
                .next("system", List.of(), List.of(AUTHOR_HISTORY))
                .turn();

        assertThat(turn).isEqualTo(new ToolCallingPort.Turn.Finished("{\"summary\":\"...\"}"));
    }

    @Test
    void reportsTheTokensTheProviderCounted() {
        ToolCallingPort.Response counted = completion(RecordingModel.answering(
                        response(AssistantMessage.builder().content("{}").build(), new DefaultUsage(2100, 180))))
                .next("system", List.of(), List.of());

        assertThat(counted.promptTokens()).isEqualTo(2100);
        assertThat(counted.completionTokens()).isEqualTo(180);
    }

    /**
     * No usage reported is unknown, not zero.
     *
     * <p>Spring AI fills an absent usage with an {@code EmptyUsage} that answers 0
     * for everything, so checking for null alone recorded a response that counted
     * nothing as a call that cost nothing. A zero is averaged into the per-call
     * figures {@code ai_invocations} exists to produce; a null is left out.
     */
    @Test
    void recordsUsageAsUnknownWhenTheProviderReportedNone() {
        ToolCallingPort.Response uncounted = completion(RecordingModel.answering(
                        new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("{}").build())))))
                .next("system", List.of(), List.of());

        assertThat(uncounted.promptTokens()).isNull();
        assertThat(uncounted.completionTokens()).isNull();
    }

    /**
     * Earlier turns go back as prose, never as function-call parts.
     *
     * <p>Gemini 3 rejects a replayed function call that lacks the thought
     * signature Spring AI 1.1.8 cannot carry, so an assistant message holding tool
     * calls would fail the second turn of every investigation. This pins the
     * workaround: what was asked for becomes assistant text, what came back
     * becomes the next user message, and a failed lookup says it failed.
     */
    @Test
    void narratesEarlierTurnsAsTextSoNoFunctionCallIsEverReplayed() throws Exception {
        ToolCall authorLookup = new ToolCall("call-1", "authorHistory", null);
        ToolCall ruleLookup = new ToolCall("call-2", "ruleText", MAPPER.readTree("{\"ruleCode\":\"ABUSE\"}"));
        RecordingModel model = RecordingModel.answering(finished("{}"));

        completion(model).next(
                "You investigate cases.",
                List.of(
                        new ToolCallingPort.Message.Prompt("A case."),
                        new ToolCallingPort.Message.ToolRequest(List.of(authorLookup, ruleLookup)),
                        new ToolCallingPort.Message.ToolOutcome(List.of(
                                ToolResult.of(authorLookup, "{\"priorActions\":2}", Set.of()),
                                ToolResult.error(ruleLookup, "No rule has the code ABUSE.")))),
                List.of(AUTHOR_HISTORY, RULE_TEXT));

        List<Message> sent = model.prompt().getInstructions();

        assertThat(sent).hasSize(4);
        assertThat(sent.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(sent.get(0).getText()).isEqualTo("You investigate cases.");
        assertThat(sent.get(1)).isInstanceOf(UserMessage.class);
        assertThat(sent.get(1).getText()).isEqualTo("A case.");
        assertThat(sent.get(2)).isInstanceOfSatisfying(AssistantMessage.class, asked -> {
            assertThat(asked.hasToolCalls()).isFalse();
            assertThat(asked.getText()).isEqualTo("I need to look up: authorHistory, ruleText({\"ruleCode\":\"ABUSE\"})");
        });
        assertThat(sent.get(3)).isInstanceOf(UserMessage.class);
        assertThat(sent.get(3).getText())
                .contains("authorHistory returned:\n{\"priorActions\":2}")
                .contains("ruleText could not be run: No rule has the code ABUSE.");
    }

    /** An empty answer is a failed call with a status, not a brief that says nothing. */
    @Test
    void treatsAnAnswerWithNothingInItAsAFailedCall() {
        assertThatThrownBy(() -> completion(RecordingModel.answering(null)).next("system", List.of(), List.of()))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.status()).isEqualTo(InvocationStatus.ERROR));

        assertThatThrownBy(() -> completion(RecordingModel.answering(new ChatResponse(List.of())))
                        .next("system", List.of(), List.of()))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.status()).isEqualTo(InvocationStatus.ERROR));
    }

    /**
     * Throttling is told apart from being broken, the same way the verdict
     * adapter tells them apart: it is the difference between waiting out a quota
     * and treating the provider as down.
     */
    @Test
    void reportsThrottlingSeparatelyFromOtherFailures() {
        RecordingModel throttled = RecordingModel.failingWith(new RuntimeException(
                "Failed to generate content", new IllegalStateException("429 . You exceeded your current quota")));

        assertThatThrownBy(() -> completion(throttled).next("system", List.of(), List.of()))
                .isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(InvocationStatus.RATE_LIMITED);
                    assertThat(failure).hasMessageContaining("exceeded your current quota");
                });
    }

    /**
     * Already a {@link ModelCallException}, so not wrapped again. Classifying it a
     * second time from its message would lose a status such as TIMEOUT, which no
     * amount of message matching can recover.
     */
    @Test
    void passesAnAlreadyClassifiedFailureThroughUntouched() {
        ModelCallException timeout = new ModelCallException(InvocationStatus.TIMEOUT, "No answer in time.");

        assertThatThrownBy(() -> completion(RecordingModel.failingWith(timeout)).next("system", List.of(), List.of()))
                .isSameAs(timeout);
    }

    private static SpringAiToolCompletion completion(ChatModel model) {
        return new SpringAiToolCompletion(model, "test-model", MAPPER);
    }

    private static ChatResponse finished(String text) {
        return response(AssistantMessage.builder().content(text).build(), null);
    }

    private static ChatResponse response(AssistantMessage message, DefaultUsage usage) {
        ChatResponseMetadata.Builder metadata = new ChatResponseMetadata.Builder();
        if (usage != null) {
            metadata.usage(usage);
        }
        return new ChatResponse(List.of(new Generation(message)), metadata.build());
    }

    /** A model that remembers the prompt it was given and answers however the test decided. */
    private static final class RecordingModel implements ChatModel {

        private final Function<Prompt, ChatResponse> answer;
        private Prompt prompt;

        private RecordingModel(Function<Prompt, ChatResponse> answer) {
            this.answer = answer;
        }

        static RecordingModel answering(ChatResponse response) {
            return new RecordingModel(prompt -> response);
        }

        static RecordingModel failingWith(RuntimeException failure) {
            return new RecordingModel(prompt -> {
                throw failure;
            });
        }

        Prompt prompt() {
            return prompt;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            return answer.apply(prompt);
        }
    }
}
