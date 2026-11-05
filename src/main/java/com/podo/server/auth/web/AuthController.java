package com.podo.server.auth.web;

import com.podo.server.auth.config.AuthProperties;
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
    private final OAuthLoginStateService states;
    private final KakaoClient kakao;
    private final AuthSessionService sessions;
    private final Clock clock;

    public AuthController(AuthProperties properties, OAuthLoginStateService states, KakaoClient kakao, AuthSessionService sessions, Clock clock) {
        this.properties = properties;
        this.states = states;
        this.kakao = kakao;
        this.sessions = sessions;
        this.clock = clock;
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
}
