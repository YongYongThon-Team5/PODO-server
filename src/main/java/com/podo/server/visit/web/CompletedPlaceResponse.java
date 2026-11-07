package com.podo.server.visit.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CompletedPlaceResponse {
    private CompletedPlaceResponse() {}

    @Schema(
            name = "CompletedPlacePage",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"items", "nextCursor", "summary"}
    )
    public record Page(
            List<Item> items,
            @Schema(types = {"string", "null"})
            String nextCursor,
            Summary summary
    ) {}

    @Schema(
            name = "CompletedPlace",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {
                    "placeId", "name", "latitude", "longitude", "districtCode",
                    "firstCompletedAt", "lastCompletedAt", "completedVisitCount",
                    "placeAvailable", "children", "hasUnassignedVisit"
            }
    )
    public record Item(
            UUID placeId,
            String name,

            @Schema(
                    types = {"number", "null"},
                    minimum = "-90",
                    maximum = "90",
                    description = "비공개 장소의 좌표가 삭제된 경우 null. 기록은 유지한다."
            )
            BigDecimal latitude,

            @Schema(
                    types = {"number", "null"},
                    minimum = "-180",
                    maximum = "180"
            )
            BigDecimal longitude,

            @Schema(types = {"string", "null"})
            String districtCode,

            Instant firstCompletedAt,
            Instant lastCompletedAt,

            @Schema(minimum = "1")
            long completedVisitCount,

            boolean placeAvailable,
            List<Child> children,
            boolean hasUnassignedVisit
    ) {}

    @Schema(
            name = "CompletedChild",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"childProfileId", "colorCode"}
    )
    public record Child(
            UUID childProfileId,
            String colorCode
    ) {}

    @Schema(
            name = "CompletionSummary",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"completedPlaceCount", "districts", "asOf"}
    )
    public record Summary(
            @Schema(minimum = "0")
            long completedPlaceCount,

            List<District> districts,
            Instant asOf
    ) {}

    @Schema(
            name = "CompletedDistrict",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"districtCode", "completedPlaceCount"}
    )
    public record District(
            @Schema(types = {"string", "null"})
            String districtCode,

            @Schema(minimum = "0")
            long completedPlaceCount
    ) {}
}