package com.podo.server.auth.web;

import com.podo.server.auth.config.AuthProperties;
import com.podo.server.auth.csrf.CsrfService;
import com.podo.server.auth.kakao.KakaoClient;
import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.auth.service.AuthenticatedAccount;
import com.podo.server.auth.service.OAuthLoginStateService;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "인증")
public class AuthController {
    private final AuthProperties properties;
    private final AuthSessionService sessions;
    private final OAuthLoginStateService states;
    private final KakaoClient kakao;
    private final CsrfService csrf;
    private final Clock clock;

    public AuthController(AuthProperties properties, AuthSessionService sessions, OAuthLoginStateService states,
            KakaoClient kakao, CsrfService csrf, Clock clock) {
        this.properties = properties;
        this.sessions = sessions;
        this.states = states;
        this.kakao = kakao;
        this.csrf = csrf;
        this.clock = clock;
    }

    @Operation(summary = "CSRF 토큰 조회", description = "선택 Bearer 또는 Refresh 세션에 결합한다. 둘 다 없으면 익명 토큰을 발급한다.")
    @GetMapping("/api/auth/csrf")
    public ResponseEntity<CsrfResponse> csrf(HttpServletRequest request,
            @AuthenticationPrincipal AuthenticatedAccount access) throws IOException {
        requireEmptyRequest(request);
        AuthenticatedAccount refresh = refreshIdentity(request, false);
        requireSameSession(access, refresh);
        AuthenticatedAccount context = access != null ? access : refresh;
        String token = csrf.issue(context == null ? null : context.sessionId());
        HttpHeaders headers = headers();
        AuthCookies.set(headers, AuthCookies.CSRF, token, Duration.ofHours(1));
        return ResponseEntity.ok().headers(headers).body(new CsrfResponse(token));
    }

    @Operation(summary = "카카오 로그인 시작", description = "브라우저에서 이동하면 카카오 인가 화면으로 리다이렉트한다.")
    @GetMapping("/api/auth/login/kakao")
    public ResponseEntity<Void> login(HttpServletRequest request) throws IOException {
        requireEmptyRequest(request);
        properties.redirect(true);
        properties.redirect(false);
        var challenge = states.issue();
        HttpHeaders headers = headers();
        headers.set(HttpHeaders.LOCATION, kakao.authorizationUri(challenge.state()));
        AuthCookies.set(headers, AuthCookies.OAUTH_STATE, challenge.browserNonce(), Duration.ofMinutes(5));
        return ResponseEntity.status(302).headers(headers).build();
    }

    @Operation(summary = "카카오 로그인 콜백", description = "등록된 콜백 전용이며 성공·오류 화면으로 이동한다.", parameters = {
            @Parameter(name = "state", in = ParameterIn.QUERY),
            @Parameter(name = "code", in = ParameterIn.QUERY),
            @Parameter(name = "error", in = ParameterIn.QUERY),
            @Parameter(name = "error_description", in = ParameterIn.QUERY)
    })
    @GetMapping("/api/auth/callback/kakao")
    public ResponseEntity<Void> callback(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params,
            HttpServletRequest request) throws IOException {
        HttpHeaders headers = headers();
        AuthCookies.clear(headers, AuthCookies.OAUTH_STATE);
        AuthCookies.clear(headers, AuthCookies.CSRF);
        boolean success = false;
        try {
            requireEmptyBody(request);
            Set<String> allowed = Set.of("state", "code", "error", "error_description");
            if (!allowed.containsAll(params.keySet()) || params.values().stream().anyMatch(v -> v.size() != 1)
                    || (params.containsKey("code") && params.containsKey("error"))) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            String state = params.getFirst("state");
            String nonce = AuthCookies.read(request, AuthCookies.OAUTH_STATE, ErrorCode.INVALID_REQUEST);
            states.consume(state, nonce);
            String code = params.getFirst("code");
            if (!params.containsKey("error") && code != null && !code.isBlank()) {
                properties.redirect(true);
                var session = sessions.issueForKakaoSubject(kakao.fetchSubject(code));
                states.linkAccount(state, session.accountId());
                AuthCookies.set(headers, AuthCookies.REFRESH, session.refreshToken(),
                        Duration.between(clock.instant(), session.expiresAt()));
                success = true;
            }
        } catch (BusinessException ex) {
            // A failed callback always returns to the configured page without provider details or tokens.
        }
        headers.setLocation(properties.redirect(success));
        return ResponseEntity.status(302).headers(headers).build();
    }

    @Operation(summary = "Access 토큰 발급·갱신", description = "본문 없이 Refresh 쿠키와 CSRF를 전송한다. Refresh는 매번 회전한다.",
            parameters = @Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER, required = true))
    @SecurityRequirement(name = "RefreshCookie")
    @PostMapping("/api/auth/token")
    public ResponseEntity<TokenResponse> token(HttpServletRequest request,
            @AuthenticationPrincipal AuthenticatedAccount access) throws IOException {
        requireOrigin(request);
        AuthenticatedAccount refresh = refreshIdentity(request, false);
        if (refresh == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        requireSameSession(access, refresh);
        verifyCsrf(request, refresh.sessionId());
        requireEmptyRequest(request);
        var pair = sessions.rotateRefresh(AuthCookies.read(request, AuthCookies.REFRESH, ErrorCode.INVALID_REQUEST));
        HttpHeaders headers = headers();
        AuthCookies.set(headers, AuthCookies.REFRESH, pair.refreshToken(),
                Duration.between(clock.instant(), pair.expiresAt()));
        return ResponseEntity.ok().headers(headers)
                .body(new TokenResponse(pair.accessToken(), "Bearer", 1800, pair.accountId()));
    }

    @Operation(summary = "현재 로그인 종료", description = "현재 Bearer 세션과 연결 토큰을 폐기한다. Refresh 쿠키는 선택이다.",
            parameters = @Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER, required = true))
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
            @AuthenticationPrincipal AuthenticatedAccount access) throws IOException {
        requireOrigin(request);
        if (access == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        requireSameSession(access, refreshIdentity(request, true));
        verifyCsrf(request, access.sessionId());
        requireEmptyRequest(request);
        sessions.revokeSession(access.sessionId());
        HttpHeaders headers = headers();
        AuthCookies.clear(headers, AuthCookies.REFRESH);
        AuthCookies.clear(headers, AuthCookies.CSRF);
        return ResponseEntity.noContent().headers(headers).build();
    }

    @Operation(summary = "내 계정 조회")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/api/me")
    public ResponseEntity<Me> me(@AuthenticationPrincipal AuthenticatedAccount account,
            HttpServletRequest request) throws IOException {
        requireEmptyRequest(request);
        if (account == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return ResponseEntity.ok().headers(headers()).body(new Me(account.accountId()));
    }

    private AuthenticatedAccount refreshIdentity(HttpServletRequest request, boolean ignoreInvalid) {
        String raw = AuthCookies.read(request, AuthCookies.REFRESH, ErrorCode.INVALID_REQUEST);
        if (raw == null) {
            return null;
        }
        try {
            return sessions.authenticateRefresh(raw);
        } catch (BusinessException ex) {
            if (ignoreInvalid && ex.getErrorCode() == ErrorCode.REFRESH_TOKEN_INVALID) {
                return null;
            }
            throw ex;
        }
    }

    private void requireSameSession(AuthenticatedAccount access, AuthenticatedAccount refresh) {
        if (access != null && refresh != null && !access.sessionId().equals(refresh.sessionId())) {
            throw new BusinessException(ErrorCode.AUTH_CONTEXT_MISMATCH);
        }
    }

    private void requireOrigin(HttpServletRequest request) {
        String origin = AuthCookies.singleHeader(request, HttpHeaders.ORIGIN, ErrorCode.CSRF_INVALID);
        if (!properties.requiredOrigin().equals(origin)) {
            throw new BusinessException(ErrorCode.CSRF_INVALID);
        }
    }

    private void verifyCsrf(HttpServletRequest request, UUID sessionId) {
        csrf.validate(AuthCookies.read(request, AuthCookies.CSRF, ErrorCode.CSRF_INVALID),
                AuthCookies.singleHeader(request, "X-CSRF-Token", ErrorCode.CSRF_INVALID), sessionId);
    }

    private void requireEmptyBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private void requireEmptyRequest(HttpServletRequest request) throws IOException {
        // Read raw input before servlet form parsing can consume a body such as "&".
        requireEmptyBody(request);
        if (!request.getParameterMap().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set("X-Request-Id", UUID.randomUUID().toString());
        return headers;
    }

    public record CsrfResponse(String csrfToken) { }
    public record TokenResponse(String accessToken, String tokenType, int expiresIn, UUID accountId) { }
    public record Me(UUID accountId) { }
}
