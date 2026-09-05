package com.campusguard.moderation.engine.ai;

/**
 * Turning a vendor's exception into something this project can act on.
 *
 * <p>Shared by the two adapters rather than written twice. They talk to the same
 * SDK and meet the same failures, and two copies of this would drift — which
 * matters because one of the two decisions here, whether a failure is worth
 * retrying, is the difference between waiting out a rate limit and hammering a
 * refused credential.
 */
public final class ProviderFailures {

    private ProviderFailures() {
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
    public static InvocationStatus classify(String detail) {
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
    public static String describe(Throwable failure) {
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

    /** Both steps together, which is what every call site actually wants. */
    public static ModelCallException asModelCallException(RuntimeException failure) {
        String detail = describe(failure);
        return new ModelCallException(classify(detail), detail, failure);
    }
}
