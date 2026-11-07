package com.podo.server.recommendation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "RecommendationOptions", requiredProperties = {"purposes", "districts", "environmentPreferences"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RecommendationOptionsResponse(
        List<Option> purposes,
        List<Option> districts,
        List<EnvironmentPreference> environmentPreferences
) {
    @Schema(name = "RecommendationOption", requiredProperties = {"code", "label", "active"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Option(@Schema(minLength = 1, maxLength = 64) String code, String label, boolean active) {
    }

    @Schema(name = "EnvironmentPreferenceOption", requiredProperties = {"code", "label"},
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record EnvironmentPreference(@Schema(allowableValues = {"INDOOR", "OUTDOOR", "ANY"}) String code,
                                        String label) {
    }
}
