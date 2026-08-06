-- V4: reports have two states, not three.
--
-- PENDING described a report that had been filed but not yet attached to a case.
-- Now that filing a report opens or joins its case in the same transaction, no
-- report is ever observable in that state, and a status nothing can be found in
-- misdescribes the workflow to everyone who reads the enum afterwards.
--
-- RESOLVED had the opposite problem: reachable in principle, but nothing ever
-- set it, because closing a case left the reports that caused it untouched. That
-- is fixed in the same change, so both remaining states now mean something.

UPDATE reports SET status = 'AGGREGATED' WHERE status = 'PENDING';

ALTER TABLE reports DROP CONSTRAINT reports_status_check;
ALTER TABLE reports ADD CONSTRAINT reports_status_check
    CHECK (status IN ('AGGREGATED', 'RESOLVED'));

ALTER TABLE reports ALTER COLUMN status SET DEFAULT 'AGGREGATED';
