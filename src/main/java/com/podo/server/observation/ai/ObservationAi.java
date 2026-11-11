package com.podo.server.observation.ai;

import java.util.List;
import java.util.UUID;

public interface ObservationAi {

    Output generate(Input input);

    record Photo(
            int position,
            String mimeType,
            byte[] bytes
    ) {
    }

    record Source(
            UUID id,
            String factText
    ) {
    }

    record Input(
            String placeName,
            List<Photo> photos,
            List<Source> sources
    ) {
    }

    record Output(
            String result,
            String introduction,
            List<Question> questions
    ) {
    }

    record Question(
            String text,
            String answer,
            String explanation,
            UUID sourceId,
            String sourceQuote,
            int imagePosition,
            String photoEvidenceText,
            String hint1,
            String hint2,
            String observationGuide
    ) {
    }

    enum FailureCode {
        UPSTREAM_TIMEOUT(true),
        UPSTREAM_UNAVAILABLE(true),
        UPSTREAM_RATE_LIMITED(true),
        INVALID_AI_RESPONSE(true),
        STORAGE_FAILURE(true),
        WORKER_INTERRUPTED(true),
        INTERNAL_FAILURE(true),

        CONFIGURATION_ERROR(false),
        SOURCE_NOT_READY(false),
        SOURCE_CHANGED(false),
        INSUFFICIENT_EVIDENCE(false),
        VISIT_CHANGED(false),
        SUPERSEDED(false);

        private final boolean retryable;

        FailureCode(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }

        public static boolean canRetry(String code) {
            if (code == null) {
                return false;
            }

            try {
                return valueOf(code).retryable;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
    }

    final class GenerationException extends RuntimeException {

        private final FailureCode code;

        public GenerationException(FailureCode code) {
            super(code.name());
            this.code = code;
        }

        public FailureCode code() {
            return code;
        }
    }
}