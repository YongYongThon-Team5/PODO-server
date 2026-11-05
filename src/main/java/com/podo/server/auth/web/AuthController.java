package com.podo.server.auth.web;

import com.podo.server.auth.config.AuthProperties;
import com.podo.server.auth.kakao.KakaoClient;
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

    public AuthController(AuthProperties properties, OAuthLoginStateService states, KakaoClient kakao) {
        this.properties = properties;
        this.states = states;
        this.kakao = kakao;
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
