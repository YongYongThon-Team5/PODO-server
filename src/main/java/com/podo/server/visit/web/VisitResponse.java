package com.podo.server.visit.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.podo.server.visit.domain.Visit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(
        name = "Visit",
        requiredProperties = {
                "id", "placeId", "placeName", "placeAvailable",
                "childProfileId", "plannedDate", "source", "status",
                "quizSetId", "answeredCount", "correctCount",
                "hintUsageCount", "reportedAt", "completedAt",
                "cancelledAt", "createdAt", "updatedAt"
        },
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE
)
public record VisitResponse(
        UUID id,
        UUID placeId,
        String placeName,
        boolean placeAvailable,

        @Schema(types = {"string", "null"}, format = "uuid")
        UUID childProfileId,

        LocalDate plannedDate,
        Visit.Source source,
        Visit.Status status,

        @Schema(types = {"string", "null"}, format = "uuid")
        UUID quizSetId,

        int answeredCount,

        @Schema(types = {"integer", "null"})
        Integer correctCount,

        int hintUsageCount,

        @Schema(types = {"string", "null"}, format = "date-time")
        Instant reportedAt,

        @Schema(types = {"string", "null"}, format = "date-time")
        Instant completedAt,

        @Schema(types = {"string", "null"}, format = "date-time")
        Instant cancelledAt,

        Instant createdAt,
        Instant updatedAt
) {}