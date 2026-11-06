package com.podo.server.global.exception;

import com.podo.server.global.response.ErrorResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException ex
    ) {
        ErrorResponse body = ErrorResponse.of(
                ex.getErrorCode(),
                ex.getDetails()
        );

        return ResponseEntity
                .status(ex.getErrorCode().getStatus())
                .headers(errorHeaders(new HttpHeaders(), body))
                .body(body);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMalformedMultipart() {
        return handleBusinessException(new BusinessException(ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception ex
    ) {
        ErrorResponse body = ErrorResponse.of(ErrorCode.INTERNAL_ERROR);
        logServerError(ex, body);

        return ResponseEntity
                .internalServerError()
                .headers(errorHeaders(new HttpHeaders(), body))
                .body(body);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        var fields = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null
                                ? "입력값을 확인해 주세요."
                                : error.getDefaultMessage()
                ))
                .toList();

        ErrorResponse body = ErrorResponse.of(
                ErrorCode.INVALID_REQUEST,
                Map.of("fields", fields)
        );

        return super.handleExceptionInternal(
                ex,
                body,
                errorHeaders(headers, body),
                status,
                request
        );
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ErrorCode errorCode = switch (status.value()) {
            case 401 -> ErrorCode.AUTHENTICATION_REQUIRED;
            case 403 -> ErrorCode.CSRF_INVALID;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 413 -> ErrorCode.UPLOAD_TOO_LARGE;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError()
                    ? ErrorCode.INVALID_REQUEST
                    : ErrorCode.INTERNAL_ERROR;
        };

        ErrorResponse error = ErrorResponse.of(errorCode);

        if (status.is5xxServerError()) {
            logServerError(ex, error);
        }

        return super.handleExceptionInternal(
                ex,
                error,
                errorHeaders(headers, error),
                status,
                request
        );
    }

    private HttpHeaders errorHeaders(
            HttpHeaders original,
            ErrorResponse body
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(original);
        headers.setCacheControl("no-store");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", body.error().requestId());

        if (body.error().code().equals(ErrorCode.AUTHENTICATION_REQUIRED.name())
                || body.error().code().equals(ErrorCode.INVALID_TOKEN.name())
                || body.error().code().equals(ErrorCode.TOKEN_EXPIRED.name())
                || body.error().code().equals(ErrorCode.REFRESH_TOKEN_INVALID.name())) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }

        return headers;
    }

    private void logServerError(
            Exception ex,
            ErrorResponse body
    ) {
        log.error(
                "Server error. requestId={}, exceptionType={}",
                body.error().requestId(),
                ex.getClass().getName()
        );
    }
}