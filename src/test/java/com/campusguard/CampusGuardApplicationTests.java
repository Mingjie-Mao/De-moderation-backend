package com.campusguard;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CampusGuardApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    /**
     * Reaching this assertion at all is the substance of the test: the context
     * only starts if Flyway applied cleanly and Hibernate's validation found
     * every mapped column present with a compatible type.
     */
    @Test
    void contextLoadsAgainstAMigratedDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Asserted as "nothing failed" rather than a migration count, so that
        // adding a migration never requires editing this test.
        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);

        assertThat(failed).isZero();
    }

    /**
     * The partial index is the reason the feed query stays cheap as soft-deleted
     * posts accumulate. It is easy to lose in a later migration without noticing,
     * so its predicate is asserted rather than assumed.
     */
    @Test
    void feedIndexExcludesSoftDeletedPosts() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String definition = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'idx_posts_forum_created'",
                String.class);

        assertThat(definition).contains("deleted_at IS NULL");
    }
}
