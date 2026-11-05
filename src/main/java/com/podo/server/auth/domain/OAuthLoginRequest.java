package com.podo.server.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "oauth_login_requests", schema = "app")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthLoginRequest {

    @Id
    @Column(name = "state_hash", columnDefinition = "text")
    private String stateHash;

    @Column(name = "browser_nonce_hash", nullable = false, columnDefinition = "text")
    private String browserNonceHash;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    public OAuthLoginRequest(String stateHash, String browserNonceHash, Instant expiresAt) {
        this.stateHash = stateHash;
        this.browserNonceHash = browserNonceHash;
        this.expiresAt = expiresAt;
    }

    public void consume(Instant now) {
        consumedAt = now;
    }

    public void linkAccount(UUID accountId) {
        this.accountId = accountId;
    }
}
