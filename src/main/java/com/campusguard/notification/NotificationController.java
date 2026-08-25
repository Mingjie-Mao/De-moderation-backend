package com.campusguard.notification;

import com.campusguard.security.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController @Validated
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service){this.service=service;}
    @GetMapping public List<NotificationView> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int size) {
        return service.list(AuthenticatedUser.idOf(jwt), size);
    }
    @GetMapping("/unread-count") public Map<String,Long> unread(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", service.unreadCount(AuthenticatedUser.idOf(jwt)));
    }
    @PostMapping("/{id}/read") public NotificationView read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.markRead(AuthenticatedUser.idOf(jwt), id);
    }
}
