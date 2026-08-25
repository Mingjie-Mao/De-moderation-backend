package com.campusguard.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(length = 254)
    private String email;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // for JPA
    }

    public User(String username, String passwordHash, UserRole role) {
        this(username, passwordHash, role, null);
    }

    public User(String username, String passwordHash, UserRole role, String email) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = UserStatus.ACTIVE;
        this.email = email == null || email.isBlank() ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
        this.displayName = username;
        this.tokenVersion = 0;
    }

    /**
     * Takes effect for new logins immediately, and for an already-issued token
     * only when that token expires. Nothing here revokes outstanding tokens; the
     * short token lifetime is what bounds the gap, and closing it properly would
     * need either a revocation list or a per-user token version.
     */
    public void ban() {
        this.status = UserStatus.BANNED;
    }

    /**
     * Lift a ban.
     *
     * <p>Reached when an administrator revises a decision that banned somebody.
     * A ban that cannot be undone means the cost of a mistaken one is permanent,
     * which is the wrong incentive for the person deciding.
     */
    public void reinstate() {
        this.status = UserStatus.ACTIVE;
    }

    public void changePassword(String encodedPassword) {
        this.passwordHash = encodedPassword;
        this.tokenVersion++;
    }

    /** Invalidates every access token issued before this call. */
    public void invalidateSessions() {
        this.tokenVersion++;
    }

    public void updateProfile(String displayName, String bio) {
        this.displayName = displayName == null || displayName.isBlank() ? username : displayName.strip();
        this.bio = bio == null || bio.isBlank() ? null : bio.strip();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName == null ? username : displayName;
    }

    public String getBio() {
        return bio;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
