package com.podo.server.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "auth_tokens", schema = "app")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthToken {

    public enum Kind {
        ACCESS, REFRESH
    }

    @Id
    @Column(name = "token_hash", columnDefinition = "text")
    private String tokenHash;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Kind kind;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public AuthToken(String tokenHash, UUID sessionId, Kind kind, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.sessionId = sessionId;
        this.kind = kind;
        this.expiresAt = expiresAt;
    }

    public void consume(Instant now) {
        consumedAt = now;
    }
}
