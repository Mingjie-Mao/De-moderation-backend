package com.campusguard.moderation.rule;

import java.util.List;

/**
 * Supplies the rules an engine judges against.
 *
 * <p>An interface for what is currently one small query, on purpose. The obvious
 * next step for a rule set is retrieval: embed the rules, and fetch only the few
 * most relevant to each piece of content. That is not worth doing here, because
 * the whole rule set is a few dozen entries and fits in a prompt with room to
 * spare, so retrieval would add a vector store, an embedding pipeline and a
 * relevance failure mode in exchange for nothing.
 *
 * <p>What it would be worth doing at a few thousand rules. Keeping the seam means
 * that change stays a new implementation of this interface rather than surgery
 * on every caller.
 */
public interface RuleProvider {

    List<ModerationRule> activeRules();
}
