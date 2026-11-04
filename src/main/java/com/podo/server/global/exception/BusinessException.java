package com.podo.server.global.exception;

import lombok.Getter;

import java.util.Map;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public BusinessException(
            ErrorCode errorCode,
            Map<String, Object> details
    ) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }
}