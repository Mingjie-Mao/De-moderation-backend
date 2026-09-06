package com.campusguard.moderation.investigation;

import com.campusguard.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asking the assistant about a case.
 *
 * <p>Under {@code /api/admin/moderation-cases} so that the filter chain on the
 * {@code /api/admin} prefix restricts it, the same way it restricts the review
 * console. A separate class from {@code AdminModerationController} because the
 * whole feature is meant to be removable: deleting this package should leave the
 * console working, minus a button.
 *
 * <p>Both endpoints are read-only as far as moderation is concerned. Nothing here
 * hides content or touches an account; that remains
 * {@code AdminModerationService.decide}, which is the only place it has ever
 * been.
 */
@RestController
@RequestMapping("/api/admin/moderation-cases")
@Tag(name = "Case investigation (admin)", description = "Evidence a reviewer would otherwise have to gather by hand")
@SecurityRequirement(name = "bearer-jwt")
public class AdminInvestigationController {

    private final InvestigationService service;

    public AdminInvestigationController(InvestigationService service) {
        this.service = service;
    }

    /**
     * The brief already held for this case, if any.
     *
     * <p>Separate from the POST so that opening a case shows an existing brief
     * without anybody being billed for it. 204 rather than 404: no brief yet is
     * an ordinary state of a case, not a missing resource.
     */
    @GetMapping("/{id}/investigation")
    @Operation(summary = "The brief already produced for this case, or 204 if there is none")
    public ResponseEntity<InvestigationBriefView> existing(@PathVariable UUID id) {
        return service.existingBrief(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * @param force ask again even though a brief is already held. For a case that
     *     has changed since — new reports, restored content — where the reviewer
     *     knowingly wants to pay for a fresh look. Default false, so the ordinary
     *     path costs nothing on a second visit.
     */
    @PostMapping("/{id}/investigate")
    @Operation(summary = "Gather the author's record and the precedent for this case's rules")
    public InvestigationBriefView investigate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean force) {

        return service.investigate(AuthenticatedUser.idOf(jwt), id, force);
    }
}
