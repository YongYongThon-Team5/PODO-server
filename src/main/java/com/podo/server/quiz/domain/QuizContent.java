package com.podo.server.quiz.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class QuizContent {
    private QuizContent() {}

    public enum Status {
        DRAFT, PUBLISHED, RETIRED, INVALIDATED
    }

    public record Set(
            UUID id,
            UUID placeId,
            UUID observationId,
            String introduction,
            int version,
            Status status,
            String reviewedBy,
            Instant reviewedAt,
            Instant publishedAt
    ) {
        public boolean usable() {
            return status == Status.PUBLISHED || status == Status.RETIRED;
        }

        public boolean reviewed() {
            return reviewedBy != null
                    && !reviewedBy.isBlank()
                    && reviewedAt != null
                    && publishedAt != null;
        }
    }

    public record Question(
            UUID id,
            int position,
            String text,
            String correctAnswer,
            String explanation,
            UUID sourceId,
            UUID sourcePlaceId,
            String sourceType,
            String sourceUrl,
            String factText,
            Instant checkedAt,
            String hint1,
            String hint2,
            String observationGuide,
            String referenceImageUrl,
            String photoEvidenceText
    ) {
        public boolean ready(UUID placeId) {
            return placeId.equals(sourcePlaceId)
                    && nonblank(text)
                    && nonblank(explanation)
                    && nonblank(factText)
                    && nonblank(hint1)
                    && nonblank(hint2)
                    && List.of("O", "X").contains(correctAnswer);
        }

        public String hint(int step) {
            return step == 1 ? hint1 : hint2;
        }

        private static boolean nonblank(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record Answer(String selectedAnswer, boolean correct) {}

    public record HintKey(UUID questionId, int step) {}

    public record Observation(
            UUID id,
            UUID visitId,
            String generationStatus
    ) {}
}