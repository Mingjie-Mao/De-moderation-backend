-- How deeply a reply is nested, and a ceiling on it.
--
-- Without this, replying to a reply had no limit, and the read path assembles a
-- thread by recursing once per level. Eight thousand links of chain answered the
-- public comments endpoint with a StackOverflowError — reachable by one account
-- replying to itself, which is to say by anyone.
--
-- The ceiling belongs here rather than in the service that happens to write
-- comments today. A check constraint holds for every writer, including a future
-- import, a backfill, or a second service; a guard in one method holds only for
-- callers who go through that method. This schema already puts its other
-- invariants in the database for the same reason.
--
-- Ten is where a conversation stops being renderable anyway: Reddit collapses
-- around there and Hacker News has given up indenting well before it.

ALTER TABLE comments ADD COLUMN depth INTEGER NOT NULL DEFAULT 0;

-- Existing rows predate the column, so their depth has to be derived from the
-- parent chain rather than assumed. Recursive because that is the shape of the
-- data; it runs once, over a table that is small at this point.
WITH RECURSIVE nested AS (
    SELECT id, 0 AS depth
    FROM comments
    WHERE parent_comment_id IS NULL

    UNION ALL

    SELECT child.id, parent.depth + 1
    FROM comments child
    JOIN nested parent ON child.parent_comment_id = parent.id
)
UPDATE comments SET depth = nested.depth
FROM nested
WHERE comments.id = nested.id;

-- Applied after the backfill: any thread that is already deeper than the ceiling
-- would otherwise make this migration fail on a database that is merely old
-- rather than wrong. NOT VALID skips the scan of existing rows and still enforces
-- the rule on every insert and update from here on.
ALTER TABLE comments
    ADD CONSTRAINT comments_depth_within_limit CHECK (depth >= 0 AND depth <= 10) NOT VALID;

-- Retrieving one subtree, and the ordering a thread is read in.
CREATE INDEX idx_comments_post_depth ON comments (post_id, depth);
