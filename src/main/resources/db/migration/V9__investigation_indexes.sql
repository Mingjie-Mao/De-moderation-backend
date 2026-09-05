-- V9: make "what happened to this author before" and "how was this rule judged
-- before" answerable.
--
-- Both questions are ones a reviewer already asks and the schema could not
-- answer. Cases are keyed by the content they concern, so there has never been
-- a path from a person to their history, and rule_codes has been queryable only
-- by reading every row.

-- Cases reached through their author, via that author's posts and comments.
--
-- The lookup is "give me the cases for these target ids", so the index is on
-- target_id alone rather than on the (target_type, target_id) pair the rest of
-- the schema uses: the ids are UUIDs and already distinguish a post from a
-- comment, and including the type would only stop the index being usable for a
-- query that does not filter on it.
--
-- Partial, because the only reason to look up a case by target outside the
-- report path is to read a decision that has already been made.
CREATE INDEX idx_moderation_cases_target_resolved
    ON moderation_cases (target_id, decided_at DESC)
    WHERE status = 'RESOLVED';

-- Precedent: cases closed with an actual outcome under a given rule.
--
-- GIN with jsonb_path_ops rather than the default operator class. The only
-- operator used against this column is containment, which is all jsonb_path_ops
-- supports, and it builds a smaller index for it.
--
-- Containment against a bare string is deliberate. rule_codes holds an array,
-- and Postgres treats `["ABUSE"] @> "ABUSE"` as true by a documented exception
-- to the rule that structures must match, so a single code can be tested
-- without constructing an array around it at every call site.
--
-- The predicate mirrors the query exactly. A case resolved with NONE is a
-- report a reviewer dismissed; it is evidence about the report, not a precedent
-- for what an outcome should be, and keeping those rows out of the index keeps
-- them out of the answer even if a future caller forgets the condition.
CREATE INDEX idx_moderation_cases_rule_codes
    ON moderation_cases USING GIN (rule_codes jsonb_path_ops)
    WHERE status = 'RESOLVED' AND final_action IS NOT NULL AND final_action <> 'NONE';
