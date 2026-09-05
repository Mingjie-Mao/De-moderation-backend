package com.campusguard.moderation.investigation;

import com.campusguard.common.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whitelist. Nothing the investigator can do lives outside this map.
 *
 * <p>Dispatch is by lookup rather than by reflection on a name the model chose,
 * which is the difference between a tool set and an arbitrary method call. A
 * name that is not a key produces an answer, not a call.
 *
 * <p>Two failures are handled differently on purpose. A model that names a tool
 * that does not exist, or sends arguments of the wrong shape, has made the most
 * ordinary mistake there is, and is told so and left to try again. A repository
 * that cannot be read has not made a mistake; that propagates and ends the
 * investigation, because narrating an infrastructure failure back to the model
 * would invite it to reason around a gap in the evidence without knowing there
 * is one.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, InvestigationTool> byName;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<InvestigationTool> tools, ObjectMapper objectMapper) {
        this.byName = tools.stream()
                .collect(Collectors.toUnmodifiableMap(InvestigationTool::name, Function.identity()));
        this.objectMapper = objectMapper;
    }

    /** What the model is told it may call. */
    public List<ToolSpec> specs() {
        return byName.values().stream()
                .map(InvestigationTool::spec)
                .sorted(java.util.Comparator.comparing(ToolSpec::name))
                .toList();
    }

    /**
     * Read-only, and declared here rather than around the investigation as a
     * whole for two reasons.
     *
     * <p>The first is that recording a model call is a legitimate write, and an
     * investigation-wide read-only transaction would refuse it — the guarantee
     * being made is that the model cannot change anything, not that nothing may
     * be written while it runs.
     *
     * <p>The second is the reason {@code ModerationCaseProcessor} splits claiming
     * from analysing: a transaction spanning the whole investigation would stay
     * open across several calls to a model, which is exactly the kind of network
     * wait a database connection should not be held for.
     */
    @Transactional(readOnly = true)
    public ToolResult execute(UUID caseId, ToolCall call) {
        InvestigationTool tool = byName.get(call.name());

        if (tool == null) {
            log.debug("Investigation for case {} asked for unknown tool '{}'.", caseId, call.name());
            return ToolResult.error(
                    call,
                    "There is no tool named '%s'. The tools you may call are: %s."
                            .formatted(call.name(), String.join(", ", byName.keySet().stream().sorted().toList())));
        }

        try {
            InvestigationTool.Output output = tool.run(caseId, arguments(call));
            return ToolResult.of(call, objectMapper.writeValueAsString(output.payload()), output.disclosedCaseIds());

        } catch (IllegalArgumentException ex) {
            return ToolResult.error(call, ex.getMessage());

        } catch (NotFoundException ex) {
            // Absence is an answer. "There is no such case" is something the model
            // can act on, and it is not a fault in the system.
            return ToolResult.error(call, ex.getMessage());

        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("A tool produced a payload that could not be serialised.", ex);
        }
    }

    /** An absent arguments object is an empty one; several tools take no arguments at all. */
    private JsonNode arguments(ToolCall call) {
        return call.arguments() == null || call.arguments().isNull()
                ? objectMapper.createObjectNode()
                : call.arguments();
    }
}
