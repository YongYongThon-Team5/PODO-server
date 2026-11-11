package com.podo.server.observation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ObservationData {

    private ObservationData() {
    }

    public record Row(
            UUID id,
            UUID visitId,
            String status,
            int attemptCount,
            String failureCode,
            String requestHash,
            UUID leaseToken,
            Instant leaseExpiresAt,
            Instant createdAt
    ) {
    }

    public record Image(
            UUID id,
            int position,
            String storageKey,
            String mimeType,
            long sizeBytes
    ) {
    }

    public record Lease(
            UUID observationId,
            UUID token
    ) {
    }

    public record ImageView(
            UUID id,
            int position,
            String contentUrl
    ) {
    }

    public record State(
            UUID id,
            UUID visitId,
            String generationStatus,
            int attemptCount,
            String failureCode,
            boolean retryAvailable,
            boolean startAvailable,
            String introduction,
            List<ImageView> images
    ) {
    }

    public record Content(
            byte[] bytes,
            String mimeType
    ) {
    }
}