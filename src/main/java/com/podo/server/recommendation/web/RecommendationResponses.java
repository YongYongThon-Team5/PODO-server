package com.podo.server.recommendation.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.podo.server.place.web.PlaceResponses.PlaceSummary;
import com.podo.server.weather.Weather;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class RecommendationResponses {
    private RecommendationResponses() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RecommendationResult", requiredProperties = {"requestId", "policyVersion", "visitDate", "purposeCode",
            "childProfileId", "searchMode", "requestedCount", "returnedCount", "shortageReason", "warnings", "items"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Result(
            String requestId,
            String policyVersion,
            LocalDate visitDate,
            String purposeCode,
            @Schema(types = {"string", "null"}, format = "uuid") UUID childProfileId,
            @Schema(allowableValues = {"RULE_BASED", "HYBRID"}) String searchMode,
            int requestedCount,
            int returnedCount,
            @Schema(types = {"string", "null"}, allowableValues = {"NO_ELIGIBLE_PLACES", "INSUFFICIENT_ELIGIBLE_PLACES"})
            String shortageReason,
            @ArraySchema(uniqueItems = true, schema = @Schema(allowableValues = {"WEATHER_UNAVAILABLE", "WEATHER_PARTIAL",
                    "SEMANTIC_SEARCH_UNAVAILABLE"})) List<String> warnings,
            @ArraySchema(maxItems = 5) List<Item> items
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RecommendationItem", requiredProperties = {"place", "matchedConditions", "weather"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Item(PlaceSummary place, List<MatchedCondition> matchedConditions, Weather weather) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "MatchedCondition", requiredProperties = {"kind", "code", "label"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record MatchedCondition(
            @Schema(allowableValues = {"PURPOSE", "INTEREST", "BOOK_GENRE", "AGE", "REGION", "ENVIRONMENT", "WEATHER"})
            String kind,
            String code,
            String label) {
    }

    /** Swagger 요청 본문 표시용. 실제 입력은 컨트롤러가 직접 검증한다. */
    @Schema(name = "RecommendationRequest", requiredProperties = {"visitDate", "purposeCode"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record RequestSchema(
            @Schema(format = "date", example = "2026-11-08") String visitDate,
            @Schema(example = "ACTIVE_PLAY") String purposeCode,
            @Schema(types = {"string", "null"}, format = "uuid") String childProfileId,
            @Schema(types = {"string", "null"}, example = "CHEOIN") String districtCode,
            @Schema(types = {"string", "null"}, allowableValues = {"INDOOR", "OUTDOOR", "ANY"}, example = "ANY")
            String environmentPreference) {
    }
}
