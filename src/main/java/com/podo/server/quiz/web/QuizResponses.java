package com.podo.server.quiz.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.podo.server.visit.domain.Visit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class QuizResponses {
    private QuizResponses() {}

    public enum Availability {
        READY, NOT_READY, CORRECTION_REQUIRED
    }

    @Schema(name = "QuizNull", types = {"null"})
    public static final class NullSchema {}

    @Schema(
            name = "NullableQuizFeedback",
            anyOf = {Feedback.class, NullSchema.class}
    )
    public static final class NullableFeedbackSchema {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(
            name = "QuizProgress",
            requiredProperties = {
                    "visitId", "quizSetId", "quizVersion", "quizAvailability",
                    "visitStatus", "introduction", "answeredCount",
                    "correctCount", "hintUsageCount", "questions",
                    "restartAvailable"
            },
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    public record Progress(
            UUID visitId,
            UUID quizSetId,
            int quizVersion,
            Availability quizAvailability,
            Visit.Status visitStatus,

            @Schema(types = {"string", "null"})
            String introduction,

            int answeredCount,

            @Schema(types = {"integer", "null"})
            Integer correctCount,

            int hintUsageCount,
            List<Question> questions,
            boolean restartAvailable
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(
            name = "QuizQuestion",
            requiredProperties = {
                    "questionId", "position", "text", "observationGuide",
                    "referenceImageUrl", "availableHintSteps",
                    "viewedHintSteps", "submittedAnswer", "feedback"
            },
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    public record Question(
            UUID questionId,
            int position,
            String text,

            @Schema(types = {"string", "null"})
            String observationGuide,

            @Schema(types = {"string", "null"}, format = "uri")
            String referenceImageUrl,

            List<Integer> availableHintSteps,
            List<Integer> viewedHintSteps,

            @Schema(
                    types = {"string", "null"},
                    description = "O / X 또는 미응답 null"
            )
            String submittedAnswer,

            @Schema(implementation = NullableFeedbackSchema.class)
            Feedback feedback
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(
            name = "QuizFeedback",
            requiredProperties = {
                    "correctAnswer", "isCorrect", "explanation",
                    "explanationSource", "sources", "photoEvidenceText"
            },
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    public record Feedback(
            String correctAnswer,
            boolean isCorrect,
            String explanation,

            @Schema(allowableValues = "STORED_CONTENT")
            String explanationSource,

            List<Source> sources,

            @Schema(types = {"string", "null"})
            String photoEvidenceText
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(
            name = "QuizSourceReference",
            requiredProperties = {"id", "type", "url", "checkedAt"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    public record Source(
            UUID id,
            String type,

            @Schema(types = {"string", "null"}, format = "uri")
            String url,

            @Schema(types = {"string", "null"}, format = "date-time")
            Instant checkedAt
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(
            name = "QuizAnswerResult",
            requiredProperties = {
                    "visitId", "questionId", "submittedAnswer", "feedback",
                    "answeredCount", "correctCount", "visitStatus",
                    "completedAt", "completionPlaceId"
            },
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    public record AnswerResult(
            UUID visitId,
            UUID questionId,
            String submittedAnswer,

            @Schema(implementation = NullableFeedbackSchema.class)
            Feedback feedback,

            int answeredCount,

            @Schema(types = {"integer", "null"})
            Integer correctCount,

            Visit.Status visitStatus,

            @Schema(types = {"string", "null"}, format = "date-time")
            Instant completedAt,

            @Schema(types = {"string", "null"}, format = "uuid")
            UUID completionPlaceId
    ) {}

    @Schema(
            name = "QuizHintResult",
            requiredProperties = {
                    "questionId", "step", "text", "hintUsageCount"
            },
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    public record HintResult(
            UUID questionId,
            int step,
            String text,
            int hintUsageCount
    ) {}
}