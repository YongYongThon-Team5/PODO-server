package com.podo.server.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(requiredProperties = {"items", "nextCursor"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CursorResponse<T>(
        List<T> items,
        @Schema(types = {"string", "null"}) String nextCursor
) {

    public CursorResponse {
        items = List.copyOf(items);
    }
}
