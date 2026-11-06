package com.podo.server.visit.domain;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "visits", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Visit {
    public enum Status {
        PLANNED, QUIZ_IN_PROGRESS, COMPLETED, CANCELLED
    }

    public enum Source {
        RECOMMENDATION, POPULAR, MAP
    }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false)
    private UUID placeId;

    @Column(updatable = false)
    private UUID childProfileId;

    @Column(nullable = false)
    private LocalDate plannedDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status;

    private UUID quizSetId;

    @Column(nullable = false, updatable = false, length = 128)
    private String createRequestKey;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String createRequestHash;

    private Instant reportedAt;
    private Instant completedAt;
    private Instant cancelledAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public static Visit planned(
            UUID accountId,
            UUID placeId,
            UUID childProfileId,
            LocalDate date,
            Source source,
            String key,
            String hash,
            Instant now
    ) {
        Visit visit = new Visit();
        visit.id = UUID.randomUUID();
        visit.accountId = accountId;
        visit.placeId = placeId;
        visit.childProfileId = childProfileId;
        visit.plannedDate = date;
        visit.source = source;
        visit.status = Status.PLANNED;
        visit.createRequestKey = key;
        visit.createRequestHash = hash;
        visit.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        visit.updatedAt = visit.createdAt;
        return visit;
    }

    public void changeDate(LocalDate date, Instant now) {
        requirePlanned();

        if (!plannedDate.equals(date)) {
            plannedDate = date;
            updatedAt = now.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public void cancel(Instant now) {
        if (status == Status.CANCELLED) {
            return;
        }

        requirePlanned();

        status = Status.CANCELLED;
        cancelledAt = now.truncatedTo(ChronoUnit.MICROS);
        updatedAt = cancelledAt;
    }

    private void requirePlanned() {
        if (status != Status.PLANNED) {
            throw new BusinessException(ErrorCode.INVALID_VISIT_STATE);
        }
    }
}