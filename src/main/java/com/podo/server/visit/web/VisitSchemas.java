package com.podo.server.visit.web;

import com.podo.server.visit.domain.Visit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

public final class VisitSchemas {
    private VisitSchemas() {}

    @Schema(
            name = "CreateVisit",
            requiredProperties = {"placeId", "plannedDate", "source"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    public record Create(
            UUID placeId,

            @Schema(
                    description = "YYYY-MM-DD. 실제 유효한 0001~9999년 날짜",
                    example = "2026-11-07"
            )
            LocalDate plannedDate,

            @Schema(
                    types = {"string", "null"},
                    format = "uuid",
                    description = "생략·null 허용. 본인 프로필만 선택"
            )
            UUID childProfileId,

            Visit.Source source
    ) {}

    @Schema(
            name = "PatchVisit",
            requiredProperties = {"plannedDate"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE
    )
    public record Patch(
            @Schema(example = "2026-11-08")
            LocalDate plannedDate
    ) {}
}