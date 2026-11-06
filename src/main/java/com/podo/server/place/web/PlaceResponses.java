package com.podo.server.place.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.podo.server.weather.Weather;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlaceResponses {
    private PlaceResponses() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "PlaceSummary", requiredProperties = {"id", "name", "address", "latitude", "longitude", "districtCode",
            "mainActivities", "environment", "thumbnailUrl", "quizAvailability", "interestCategoryIds"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PlaceSummary(
            UUID id,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            @Schema(types = {"string", "null"}) String districtCode,
            List<String> mainActivities,
            @Schema(allowableValues = {"INDOOR", "OUTDOOR", "MIXED", "UNKNOWN"}) String environment,
            @Schema(types = {"string", "null"}, format = "uri") String thumbnailUrl,
            @Schema(allowableValues = {"READY", "NOT_READY", "CORRECTION_REQUIRED"}) String quizAvailability,
            @ArraySchema(uniqueItems = true) List<UUID> interestCategoryIds
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "PlaceDetail", requiredProperties = {"id", "name", "address", "latitude", "longitude", "districtCode",
            "mainActivities", "environment", "thumbnailUrl", "quizAvailability", "description", "images", "openingHours",
            "feeInfo", "targetAgeInfo", "amenities", "cautions", "sources", "weather", "mapLinks", "interestCategoryIds"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PlaceDetail(
            UUID id,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            @Schema(types = {"string", "null"}) String districtCode,
            List<String> mainActivities,
            @Schema(allowableValues = {"INDOOR", "OUTDOOR", "MIXED", "UNKNOWN"}) String environment,
            @Schema(types = {"string", "null"}, format = "uri") String thumbnailUrl,
            @Schema(allowableValues = {"READY", "NOT_READY", "CORRECTION_REQUIRED"}) String quizAvailability,
            @Schema(types = {"string", "null"}) String description,
            List<PlaceImage> images,
            @Schema(types = {"string", "null"}) String openingHours,
            @Schema(types = {"string", "null"}) String feeInfo,
            @Schema(types = {"string", "null"}) String targetAgeInfo,
            @Schema(types = {"array", "null"}) List<String> amenities,
            @Schema(types = {"array", "null"}) List<String> cautions,
            List<SourceReference> sources,
            @Schema(types = {"object", "null"}) Weather weather,
            MapLinks mapLinks,
            @ArraySchema(uniqueItems = true) List<UUID> interestCategoryIds
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "PlaceImage", requiredProperties = {"id", "url", "position", "sourceUrl"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record PlaceImage(UUID id, @Schema(format = "uri") String url, int position,
                             @Schema(types = {"string", "null"}, format = "uri") String sourceUrl) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "SourceReference", requiredProperties = {"id", "type", "url", "checkedAt"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SourceReference(UUID id,
                                  @Schema(allowableValues = {"OFFICIAL", "PUBLIC_DATA", "FIELD_NOTE"}) String type,
                                  @Schema(types = {"string", "null"}, format = "uri") String url,
                                  @Schema(types = {"string", "null"}) Instant checkedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "PlaceMapLinks", requiredProperties = {"placeUrl", "directionsUrl"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record MapLinks(@Schema(types = {"string", "null"}, format = "uri") String placeUrl,
                           @Schema(types = {"string", "null"}, format = "uri") String directionsUrl) {
    }
}
