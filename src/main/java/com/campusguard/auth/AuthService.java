package com.campusguard.auth;

import com.campusguard.common.ConflictException;
import com.campusguard.security.TokenIssuer;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserRole;
import com.campusguard.user.UserStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenService refreshTokens;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokenIssuer,
            RefreshTokenService refreshTokens) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokens = refreshTokens;
    }

    /**
     * Everyone registers as a member. There is no way to ask for the admin role
     * over the wire, because an endpoint that grants privilege on request is an
     * endpoint that grants privilege to attackers.
     */
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new ConflictException("That username is taken.");
        }

        User user = userRepository.saveAndFlush(new User(
                request.username(), passwordEncoder.encode(request.password()), UserRole.MEMBER, request.email()));

        return issueFor(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository
                .findByUsername(request.username())
                .orElseThrow(AuthService::genericFailure);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw genericFailure();
        }

        // Told plainly, unlike a wrong password: someone who has been suspended
        // needs to know that is what happened, and they already proved they own
        // the account.
        switch (user.getStatus()) {
            case BANNED -> throw new LockedException("This account has been banned.");
            case SUSPENDED -> throw new DisabledException("This account is suspended.");
            case ACTIVE -> {
                // proceed
            }
        }

        return issueFor(user);
    }

    private TokenResponse issueFor(User user) {
        return TokenResponse.bearer(
                tokenIssuer.issue(user),
                refreshTokens.issue(user),
                tokenIssuer.ttlSeconds(),
                user.getId(),
                user.getUsername());
    }

    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        User user = refreshTokens.consume(request.refreshToken());
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new LockedException("This account can no longer sign in.");
        }
        return issueFor(user);
    }

    @Transactional
    public void changePassword(java.util.UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId).orElseThrow(AuthService::genericFailure);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw genericFailure();
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ConflictException("The new password must differ from the current password.");
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        refreshTokens.revokeAll(userId);
    }

    @Transactional
    public void logoutEverywhere(java.util.UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(AuthService::genericFailure);
        user.invalidateSessions();
        refreshTokens.revokeAll(userId);
    }

    /**
     * One message for both an unknown username and a wrong password. Telling them
     * apart turns the login form into a way to enumerate who has an account.
     */
    private static BadCredentialsException genericFailure() {
        return new BadCredentialsException("Invalid username or password.");
    }
}
