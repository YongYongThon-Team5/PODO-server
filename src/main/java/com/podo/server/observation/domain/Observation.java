package com.podo.server.observation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "observations", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Observation {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID visitId;

    @Column(nullable = false, columnDefinition = "text")
    private String storageKey;

    @Column(nullable = false, columnDefinition = "text")
    private String mimeType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, columnDefinition = "text")
    private String generationStatus;

    @Column(columnDefinition = "text")
    private String aiModel;

    @Column(columnDefinition = "text")
    private String promptVersion;

    @Column(columnDefinition = "text")
    private String failureCode;

    private Instant generatedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}