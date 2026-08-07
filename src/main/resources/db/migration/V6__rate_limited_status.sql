-- V6: being throttled is not the same as being broken.
--
-- Rate limiting was landing in the catch-all ERROR bucket alongside refused
-- credentials and transport failures. Those need opposite responses: a bad key
-- will still be bad in thirty seconds, while a 429 is the provider saying "not
-- yet" and is the one failure that retrying actually fixes. Counting them
-- together also makes the failure rate unreadable, since a healthy service under
-- load and a dead one produce the same number.

ALTER TABLE ai_invocations DROP CONSTRAINT ai_invocations_status_check;
ALTER TABLE ai_invocations ADD CONSTRAINT ai_invocations_status_check
    CHECK (status IN ('SUCCESS', 'INVALID_RESPONSE', 'TIMEOUT', 'CIRCUIT_OPEN', 'RATE_LIMITED', 'ERROR'));
