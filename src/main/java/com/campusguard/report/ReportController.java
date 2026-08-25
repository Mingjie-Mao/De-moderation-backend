package com.campusguard.report;

import com.campusguard.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports", description = "User-submitted reports about posts and comments")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Report a post or comment")
    public ReportResponse create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateReportRequest request) {
        return reportService.create(AuthenticatedUser.idOf(jwt), request);
    }

    @GetMapping("/{id}")
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Fetch a report; readable by its reporter or an administrator")
    public ReportResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return reportService.get(AuthenticatedUser.idOf(jwt), id);
    }
}
