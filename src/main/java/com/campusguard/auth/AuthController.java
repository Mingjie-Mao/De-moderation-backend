package com.campusguard.auth;

import com.campusguard.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Account registration and token issuing")
public class AuthController {

    private final AuthService authService;
    private final RequestRateLimiter rateLimiter;
    private final AuthRateLimitProperties limits;
    private final PasswordResetService passwordReset;

    public AuthController(
            AuthService authService,
            RequestRateLimiter rateLimiter,
            AuthRateLimitProperties limits,
            PasswordResetService passwordReset) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.passwordReset = passwordReset;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account and receive a token")
    public TokenResponse register(
            HttpServletRequest http, @Valid @RequestBody RegisterRequest request) {
        rateLimiter.consume("register-ip", http.getRemoteAddr(), limits.registrationsPerIp(), limits.window());
        return authService.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for a bearer token")
    public TokenResponse login(HttpServletRequest http, @Valid @RequestBody LoginRequest request) {
        rateLimiter.consume("login-ip", http.getRemoteAddr(), limits.loginsPerIp(), limits.window());
        rateLimiter.consume("login-account", request.username(), limits.loginsPerAccount(), limits.window());
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token and receive a new access/refresh pair")
    public TokenResponse refresh(
            HttpServletRequest http, @Valid @RequestBody RefreshTokenRequest request) {
        rateLimiter.consume("refresh-ip", http.getRemoteAddr(), limits.refreshesPerIp(), limits.window());
        return authService.refresh(request);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Change the current password and end every existing session")
    public void changePassword(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(AuthenticatedUser.idOf(jwt), request);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "End every session for the current account")
    public void logoutEverywhere(@AuthenticationPrincipal Jwt jwt) {
        authService.logoutEverywhere(AuthenticatedUser.idOf(jwt));
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request a one-time password reset email")
    public void requestPasswordReset(
            HttpServletRequest http, @Valid @RequestBody PasswordResetRequest request) {
        rateLimiter.consume("password-reset-ip", http.getRemoteAddr(), limits.registrationsPerIp(), limits.window());
        passwordReset.request(request.account());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Use a one-time reset token and end existing sessions")
    public void confirmPasswordReset(
            HttpServletRequest http, @Valid @RequestBody PasswordResetConfirmRequest request) {
        rateLimiter.consume("password-reset-confirm-ip", http.getRemoteAddr(), limits.loginsPerIp(), limits.window());
        passwordReset.confirm(request);
    }
}
