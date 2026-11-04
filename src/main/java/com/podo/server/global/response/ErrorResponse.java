package com.podo.server.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.podo.server.global.exception.ErrorCode;

import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorResponse(ErrorDetail error) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, Map.of());
    }

    public static ErrorResponse of(
            ErrorCode errorCode,
            Map<String, Object> details
    ) {
        return new ErrorResponse(new ErrorDetail(
                errorCode.name(),
                errorCode.getMessage(),
                Map.copyOf(details),
                UUID.randomUUID().toString()
        ));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ErrorDetail(
            String code,
            String message,
            Map<String, Object> details,
            String requestId
    ) {
    }
}