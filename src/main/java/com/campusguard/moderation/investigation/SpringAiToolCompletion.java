package com.campusguard.moderation.investigation;

import com.campusguard.moderation.engine.ai.InvocationStatus;
import com.campusguard.moderation.engine.ai.ModelCallException;
import com.campusguard.moderation.engine.ai.ProviderFailures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The one place this project touches a vendor's tool-calling API.
 *
 * <p>The sibling of {@code SpringAiChatCompletion}, and separate from it for the
 * same reason that one exists: everything above works in terms of a port, so
 * replacing the provider is a second implementation of one class, and the loop
 * can be tested against a scripted model with no network and no key.
 *
 * <p><b>Tool execution is switched off.</b> Spring AI will happily run the tools
 * itself and hand back only the final text, which is the convenient default and
 * the wrong one here: the step budget, the whitelist, the read-only transaction
 * and the record of what was disclosed all live in {@code CaseInvestigator}, and
 * a framework loop underneath it would bypass every one of them. What is wanted
 * from the SDK is one turn.
 */
public class SpringAiToolCompletion implements ToolCallingPort {

    private final ChatModel chatModel;
    private final String modelName;
    private final ObjectMapper objectMapper;

    public SpringAiToolCompletion(ChatModel chatModel, String modelName, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.modelName = modelName;
        this.objectMapper = objectMapper;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public Response next(
            String system, List<ToolCallingPort.Message> history, List<ToolSpec> tools) {
        try {
            List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(system));
            history.forEach(entry -> messages.add(toSpringAi(entry)));

            ChatResponse response = chatModel.call(new Prompt(
                    messages,
                    GoogleGenAiChatOptions.builder()
                            .model(modelName)
                            // The same reasoning as the engine's: an investigation
                            // that reached a different conclusion on a re-run would
                            // be unreviewable, and there is nothing to be gained
                            // from variety in an evidence summary.
                            .temperature(0.0)
                            .toolCallbacks(tools.stream().map(this::advertise).toList())
                            .internalToolExecutionEnabled(false)
                            .build()));

            if (response == null || response.getResult() == null) {
                throw new ModelCallException(InvocationStatus.ERROR, "The provider returned no result.");
            }

            AssistantMessage answer = response.getResult().getOutput();

            Turn turn = answer.hasToolCalls()
                    ? new Turn.CallTools(answer.getToolCalls().stream().map(this::toToolCall).toList())
                    : new Turn.Finished(answer.getText() == null ? "" : answer.getText());

            // Spring AI stands in for a response that reported no usage with an
            // EmptyUsage, which answers 0. Read as a count, that records the call
            // as having cost nothing rather than as unknown.
            Integer promptTokens = null;
            Integer completionTokens = null;
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (usage != null && !(usage instanceof EmptyUsage)) {
                promptTokens = asInt(usage.getPromptTokens());
                completionTokens = asInt(usage.getCompletionTokens());
            }

            return new Response(turn, promptTokens, completionTokens);

        } catch (ModelCallException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ProviderFailures.asModelCallException(ex);
        }
    }

    /**
     * A tool the model is told about but that this callback will never run.
     *
     * <p>Spring AI's only way to describe a tool is a {@link ToolCallback}, whose
     * job is normally both to declare and to execute. With internal execution off
     * the second half is dead code, and it throws rather than silently returning
     * something: if the framework ever did call it, the investigation would be
     * running tools outside the whitelist and the budget, and that should be a
     * loud failure rather than a quiet one.
     */
    private ToolCallback advertise(ToolSpec spec) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(writeSchema(spec))
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException(
                        "Spring AI tried to execute '%s' itself. Tool execution belongs to CaseInvestigator, "
                                .formatted(spec.name())
                                + "which owns the whitelist, the step budget and the read-only transaction.");
            }
        };
    }

    private ToolCall toToolCall(AssistantMessage.ToolCall call) {
        try {
            String arguments = call.arguments();
            return new ToolCall(
                    call.id(),
                    call.name(),
                    arguments == null || arguments.isBlank() ? null : objectMapper.readTree(arguments));

        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Arguments that are not JSON are the model's mistake, and the registry
            // is where a mistake becomes an answer. Null reaches it as an empty
            // arguments object, and the tool then says which field is missing.
            return new ToolCall(call.id(), call.name(), null);
        }
    }

    /**
     * Replays the conversation as text rather than as function-call parts.
     *
     * <p>Not the obvious mapping, and the reason is a hard constraint rather than
     * a preference. Gemini 3 models attach a {@code thoughtSignature} to every
     * function call and reject a later turn that sends the call back without it —
     * {@code 400 . Function call is missing a thought_signature in functionCall
     * parts}. The field exists on {@code com.google.genai.types.Part}, but Spring
     * AI 1.1.8's adapter neither reads nor writes it, so a signature cannot be
     * carried across a turn through this library at all. Rebuilding an
     * {@code AssistantMessage} from our own records loses something we were never
     * given.
     *
     * <p>So the previous turn is narrated: what the model asked for becomes
     * assistant text, and what the tools returned becomes the next user message.
     * No {@code functionCall} part is ever sent back, so nothing is missing from
     * one. The model still calls tools — the declarations go with every request —
     * it just reads its own history as prose.
     *
     * <p>The cost is a few tokens and a little fidelity. The alternatives were
     * worse: pinning the assistant to a Gemini 2 model would put it outside the
     * circuit that protects the queue, which is the one thing
     * {@link ResilientToolCalling} exists to prevent.
     *
     * <p>Confined to this class on purpose. It is a fact about one provider and
     * one library version, and {@link ToolCallingPort} is what keeps it from
     * being a fact about the investigator.
     */
    private org.springframework.ai.chat.messages.Message toSpringAi(ToolCallingPort.Message entry) {
        return switch (entry) {
            case ToolCallingPort.Message.Prompt prompt -> new UserMessage(prompt.text());

            case ToolCallingPort.Message.ToolRequest request -> new AssistantMessage(
                    "I need to look up: "
                            + request.calls().stream().map(this::describe).collect(Collectors.joining(", ")));

            case ToolCallingPort.Message.ToolOutcome outcome -> new UserMessage(
                    outcome.results().stream().map(this::describe).collect(Collectors.joining("\n\n")));
        };
    }

    private String describe(ToolCall call) {
        return call.arguments() == null
                ? call.name()
                : call.name() + "(" + call.arguments() + ")";
    }

    /** A failed lookup is reported as failed. Silently returning its message as data would read as a finding. */
    private String describe(ToolResult result) {
        return result.error()
                ? "%s could not be run: %s".formatted(result.name(), result.content())
                : "%s returned:\n%s".formatted(result.name(), result.content());
    }

    private String writeSchema(ToolSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec.parameters());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Tool '" + spec.name() + "' has a schema that could not be serialised.", ex);
        }
    }

    private Integer asInt(Number value) {
        return value == null ? null : value.intValue();
    }
}
