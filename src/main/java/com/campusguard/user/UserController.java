package com.campusguard.user;

import com.campusguard.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public MyProfileView me(@AuthenticationPrincipal Jwt jwt) {
        return service.me(AuthenticatedUser.idOf(jwt));
    }

    @PatchMapping("/me")
    public MyProfileView update(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        return service.update(AuthenticatedUser.idOf(jwt), request);
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable UUID id) {
        return service.get(id);
    }
}
