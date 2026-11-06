package com.podo.server.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "quiz_hint_views", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizHintView {

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "visit_id", nullable = false)
        private UUID visitId;

        @Column(name = "question_id", nullable = false)
        private UUID questionId;

        @Column(nullable = false)
        private short step;
    }

    @EmbeddedId
    private Id id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "question_id",
            insertable = false,
            updatable = false
    )
    private QuizQuestion question;

    @Column(nullable = false)
    private Instant firstViewedAt;

    public QuizHintView(
            UUID visitId,
            UUID questionId,
            int step,
            Instant now
    ) {
        if (step != 1 && step != 2) {
            throw new IllegalArgumentException("Invalid hint step");
        }

        id = new Id(visitId, questionId, (short) step);
        firstViewedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
}