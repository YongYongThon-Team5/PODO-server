package com.podo.server.weather;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "Weather", requiredProperties = {"targetDate", "condition", "unavailableReason", "forecastAt", "checkedAt"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record Weather(
        LocalDate targetDate,
        @Schema(allowableValues = {"CLEAR", "CLOUDY", "RAIN", "UNKNOWN"}) String condition,
        @Schema(types = {"string", "null"},
                allowableValues = {"OUT_OF_FORECAST_RANGE", "PROVIDER_FAILURE", "UNSUPPORTED_CONDITION", "LOCATION_UNAVAILABLE"})
        String unavailableReason,
        @Schema(types = {"string", "null"}) OffsetDateTime forecastAt,
        OffsetDateTime checkedAt
) {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static Weather unknown(LocalDate targetDate, String reason) {
        return new Weather(targetDate, "UNKNOWN", reason, null, OffsetDateTime.now(SEOUL));
    }
}
