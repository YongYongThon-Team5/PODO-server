package com.podo.server.auth.security;

import com.podo.server.global.exception.ErrorCode;
import com.podo.server.global.response.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorWriter {
    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        ErrorResponse body = ErrorResponse.of(errorCode);
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Request-Id", body.error().requestId());
        if (errorCode.getStatus().value() == 401) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
