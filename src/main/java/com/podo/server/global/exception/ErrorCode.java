package com.podo.server.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    INVALID_DATE(HttpStatus.BAD_REQUEST, "날짜를 확인해 주세요."),
    INVALID_OPTION(HttpStatus.BAD_REQUEST, "선택 항목을 확인해 주세요."),
    INVALID_TAG_KIND(HttpStatus.BAD_REQUEST, "분류 종류를 확인해 주세요."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "목록을 처음부터 다시 조회해 주세요."),

    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증이 만료되었습니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "요청 검증에 실패했습니다."),

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없습니다."),

    ACTIVE_VISIT_EXISTS(HttpStatus.CONFLICT, "진행 중인 방문이 있습니다."),
    INVALID_VISIT_STATE(HttpStatus.CONFLICT, "현재 방문 상태에서는 처리할 수 없습니다."),
    ANSWER_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "이미 다른 답변을 제출한 문항입니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 요청 키에 다른 내용이 전달되었습니다."),
    QUIZ_NOT_READY(HttpStatus.CONFLICT, "이 장소의 퀴즈를 준비하고 있습니다."),
    QUIZ_CONTENT_CHANGED(HttpStatus.CONFLICT, "퀴즈를 정정하고 있습니다."),
    PLACE_UNAVAILABLE(HttpStatus.CONFLICT, "현재 이용할 수 없는 장소입니다."),
    COLOR_ALREADY_USED(HttpStatus.CONFLICT, "다른 아이가 사용 중인 색상입니다."),
    AUTH_CONTEXT_MISMATCH(HttpStatus.CONFLICT, "로그인 정보를 다시 확인해 주세요."),

    UPLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "사진의 허용 크기를 초과했습니다."),
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 사진 형식입니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 많습니다. 잠시 후 다시 시도해 주세요."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "잠시 후 다시 시도해 주세요."),
    OPTIONS_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "선택 목록을 준비하고 있습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}