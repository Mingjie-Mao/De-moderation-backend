package com.campusguard.moderation.investigation;

import com.campusguard.moderation.admin.AdminModerationService;
import com.campusguard.moderation.admin.ModerationCaseDetail;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The case itself: the content, the engine's verdict, and the trail so far.
 *
 * <p>The same view the reviewer is looking at, from
 * {@link AdminModerationService#get}, rather than a second assembly of the same
 * three pieces. If the console and the assistant ever disagreed about what a
 * case says, the bug would be in whichever one had its own copy of this.
 *
 * <p>Offered as a tool even though the loop opens with a summary of the case,
 * because the summary omits the audit trail. A model that wants to know whether
 * this content was already restored once should be able to ask.
 */
@Component
public class CaseDetailTool implements InvestigationTool {

    private final AdminModerationService cases;

    public CaseDetailTool(AdminModerationService cases) {
        this.cases = cases;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "caseDetail",
                "The full record of the case you are investigating: the reported content, the "
                        + "engine's recommendation, and every audited event on it so far. Call this "
                        + "when you need the history of the case itself rather than of its author.",
                ToolSpec.noArguments());
    }

    @Override
    public Output run(UUID caseId, JsonNode arguments) {
        ModerationCaseDetail detail = cases.get(caseId);
        return Output.of(detail, Set.of(caseId));
    }
}
