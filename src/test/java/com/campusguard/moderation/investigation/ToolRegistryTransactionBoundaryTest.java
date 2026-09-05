package com.campusguard.moderation.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Who establishes the read-only boundary.
 *
 * <p>{@code ToolRegistryIntegrationTest} shows that the tools do not write when
 * the caller wraps them in a read-only transaction. That is a weaker claim than
 * it looks: it holds just as well if the registry contributes nothing and the
 * caller happens to be careful. This asserts the stronger one — that a tool is
 * read-only because the registry says so, whatever the caller did or forgot.
 *
 * <p>Worth its own class and its own application context, because proving it
 * needs a tool that tries to write, and such a tool must not exist in the real
 * registry.
 */
class ToolRegistryTransactionBoundaryTest extends AbstractIntegrationTest {

    @Autowired
    private ToolRegistry registry;

    /**
     * No surrounding transaction at all, which is how a controller would call
     * this. If the boundary lived at the call site rather than in the registry,
     * the write below would succeed.
     */
    @Test
    void theRegistryItselfMakesEveryToolReadOnly() throws Exception {
        ToolResult result = registry.execute(
                UUID.randomUUID(), new ToolCall("call_probe", "transactionProbe", null));

        assertThat(result.error()).isFalse();

        JsonNode payload = objectMapper.readTree(result.content());
        assertThat(payload.get("transactionActive").asBoolean()).isTrue();
        assertThat(payload.get("markedReadOnly").asBoolean()).isTrue();
        assertThat(payload.get("writeRefusedBy").asText()).contains("read-only");
    }

    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        InvestigationTool transactionProbe(JdbcTemplate jdbcTemplate) {
            return new InvestigationTool() {

                @Override
                public ToolSpec spec() {
                    return new ToolSpec("transactionProbe", "Test only.", ToolSpec.noArguments());
                }

                @Override
                public Output run(UUID caseId, JsonNode arguments) {
                    String refusal;
                    try {
                        // Matches no rows on purpose. PostgreSQL rejects an UPDATE in
                        // a read-only transaction before it looks at what it would
                        // touch, so this proves the guard without relying on it.
                        jdbcTemplate.update(
                                "update moderation_cases set report_count = report_count where id = ?",
                                UUID.fromString("00000000-0000-0000-0000-000000000000"));
                        refusal = "the write was allowed";
                    } catch (RuntimeException ex) {
                        refusal = String.valueOf(ex.getMessage());
                    }

                    return Output.withoutCases(new Probe(
                            TransactionSynchronizationManager.isActualTransactionActive(),
                            TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                            refusal));
                }
            };
        }

        record Probe(boolean transactionActive, boolean markedReadOnly, String writeRefusedBy) {
        }
    }
}
