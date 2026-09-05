package com.campusguard.moderation.investigation;

import java.util.List;

/**
 * The narrowest useful view of a model that can call tools: one turn in, one
 * turn out.
 *
 * <p>The sibling of {@code ChatCompletionPort}, and deliberately not an
 * extension of it. A completion is a question with an answer; a turn is a
 * question with two possible kinds of answer, and collapsing the second into the
 * first would mean parsing tool calls out of prose somewhere above this line.
 *
 * <p>One turn, not one investigation. The loop, the step budget and the decision
 * to stop live above this interface, so all three can be exercised against a
 * stub that returns scripted turns with no network and no credential.
 */
public interface ToolCallingPort {

    /** Recorded on every call, so a change of model can be attributed after the fact. */
    String modelName();

    Response next(String system, List<Message> history, List<ToolSpec> tools);

    /**
     * @param promptTokens null when the provider does not report usage. Recorded
     *     as unknown rather than guessed: a fabricated count would quietly corrupt
     *     the figures the invocation table exists to produce.
     */
    record Response(Turn turn, Integer promptTokens, Integer completionTokens) {
    }

    /** What the model did with its turn. */
    sealed interface Turn {

        /** It wants to look something up first. */
        record CallTools(List<ToolCall> calls) implements Turn {
        }

        /** It is done looking and has written its brief. */
        record Finished(String text) implements Turn {
        }
    }

    /**
     * The conversation so far.
     *
     * <p>The investigator's entire state. It lives for one request, is never
     * written down and never spans two investigations: what has to be remembered
     * about a case is already in PostgreSQL, with transactions and an audit trail
     * behind it, and a second copy kept by a model would be a second answer to
     * questions that must have exactly one.
     */
    sealed interface Message {

        /** The opening description of the case, and any correction sent afterwards. */
        record Prompt(String text) implements Message {
        }

        record ToolRequest(List<ToolCall> calls) implements Message {
        }

        record ToolOutcome(List<ToolResult> results) implements Message {
        }
    }
}
