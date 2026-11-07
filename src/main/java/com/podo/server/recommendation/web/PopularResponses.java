package com.podo.server.recommendation.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.podo.server.place.web.PlaceResponses.PlaceSummary;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class PopularResponses {
    private PopularResponses() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "PopularResult", requiredProperties = {"items", "aggregation", "emptyReason"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Result(
            @ArraySchema(maxItems = 15) List<Item> items,
            Aggregation aggregation,
            @Schema(types = {"string", "null"}, allowableValues = {"COLLECTING"}) String emptyReason) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "PopularItem", requiredProperties = {"rank", "place", "visitCount"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Item(@Schema(minimum = "1", maximum = "15") int rank, PlaceSummary place,
                       @Schema(minimum = "1") long visitCount) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "PopularityAggregation", requiredProperties = {"basis", "period", "deduplication", "tieBreak",
            "asOf", "policyVersion"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Aggregation(
            @Schema(allowableValues = {"REPORTED_DISTINCT_ACCOUNTS"}) String basis,
            Period period,
            @Schema(allowableValues = {"ACCOUNT_PER_PLACE"}) String deduplication,
            @Schema(allowableValues = {"NAME_ASC_ID_ASC"}) String tieBreak,
            OffsetDateTime asOf,
            String policyVersion) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "PopularityPeriod", requiredProperties = {"type", "fromDate", "toDate"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Period(@Schema(allowableValues = {"ALL_TIME"}) String type,
                         @Schema(types = {"null"}) String fromDate,
                         @Schema(types = {"null"}) String toDate) {
    }
}
