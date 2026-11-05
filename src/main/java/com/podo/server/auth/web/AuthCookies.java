package com.podo.server.auth.web;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Collections;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

public final class AuthCookies {
    public static final String REFRESH = "__Host-refresh";
    public static final String OAUTH_STATE = "__Host-oauth-state";
    public static final String CSRF = "__Host-csrf";

    private AuthCookies() {
    }

    public static String read(HttpServletRequest request, String name, ErrorCode invalid) {
        String value = null;
        boolean seen = false;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (name.equals(cookie.getName())) {
                    if (seen) {
                        throw new BusinessException(invalid);
                    }
                    seen = true;
                    value = cookie.getValue();
                }
            }
        }
        return value;
    }

    public static String singleHeader(HttpServletRequest request, String name, ErrorCode invalid) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) {
            throw new BusinessException(invalid);
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    public static void set(HttpHeaders headers, String name, String value, Duration maxAge) {
        String sameSite = CSRF.equals(name) ? "Strict" : "Lax";
        headers.add(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value)
                .httpOnly(true).secure(true).path("/").sameSite(sameSite).maxAge(maxAge).build().toString());
    }

    public static void clear(HttpHeaders headers, String name) {
        set(headers, name, "", Duration.ZERO);
    }
}
