package com.podo.server.profile.image.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "profile_images", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProfileImage {
    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "storage_key", nullable = false, columnDefinition = "text")
    private String storageKey;

    @Column(name = "mime_type", nullable = false, columnDefinition = "text")
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "orphaned_at")
    private Instant orphanedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ProfileImage(UUID id, UUID accountId, String storageKey, String mimeType, long sizeBytes,
                        Instant now) {
        this.id = id;
        this.accountId = accountId;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        this.orphanedAt = createdAt;
    }

    public void markLinked() {
        this.orphanedAt = null;
    }

    public void markOrphaned(Instant now) {
        this.orphanedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}
