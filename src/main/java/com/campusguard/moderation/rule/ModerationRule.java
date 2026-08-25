package com.campusguard.moderation.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "moderation_rules")
public class ModerationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String title;

    /**
     * The rule as a person would read it. The keyword engine ignores this, but it
     * is what a language model is given, and what an administrator sees next to
     * a verdict.
     */
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleSeverity severity;

    // Stored as JSONB rather than a child table: terms are read as a whole list,
    // never queried individually, and never joined against.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> terms;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModerationRule() {
        // for JPA
    }

    public ModerationRule(String code, String title, String body, RuleSeverity severity, List<String> terms) {
        this.code = code;
        this.title = title;
        this.body = body;
        this.severity = severity;
        this.terms = terms == null ? List.of() : List.copyOf(terms);
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public RuleSeverity getSeverity() {
        return severity;
    }

    public List<String> getTerms() {
        return terms == null ? List.of() : terms;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
