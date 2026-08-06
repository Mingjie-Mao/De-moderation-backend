package com.campusguard.moderation.admin;

import com.campusguard.moderation.CaseStatus;
import com.campusguard.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The moderation console.
 *
 * <p>Served through Swagger UI rather than a separate front end. Administrators
 * are a handful of people doing a low-volume task, and a bespoke interface would
 * be a second application to build and secure for no benefit they would notice.
 *
 * <p>Access is enforced by the filter chain on the {@code /api/admin} prefix
 * rather than annotation by annotation, so a new endpoint added here is
 * restricted by default instead of restricted if somebody remembers.
 */
@RestController
@RequestMapping("/api/admin/moderation-cases")
@Tag(name = "Moderation (admin)", description = "Review queue and case decisions")
@SecurityRequirement(name = "bearer-jwt")
public class AdminModerationController {

    private final AdminModerationService service;

    public AdminModerationController(AdminModerationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List cases in a given state, oldest first")
    public List<ModerationCaseView> list(
            @RequestParam(defaultValue = "AWAITING_REVIEW") CaseStatus status,
            @PageableDefault(size = 50) Pageable pageable) {
        return service.list(status, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a case with its content and full audit trail")
    public ModerationCaseDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/decision")
    @Operation(summary = "Resolve a case by taking an action on the reported content")
    public ModerationCaseDetail decide(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody CaseDecisionRequest request) {
        return service.decide(AuthenticatedUser.idOf(jwt), id, request);
    }
}
