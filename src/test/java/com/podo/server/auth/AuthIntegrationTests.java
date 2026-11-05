package com.podo.server.auth;

import com.podo.server.auth.kakao.KakaoClient;
import com.podo.server.auth.service.AuthCleanupService;
import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.auth.service.OAuthLoginStateService;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@Testcontainers
@Import(AuthIntegrationTests.TestClockConfiguration.class)
class AuthIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final String REFRESH = "__Host-refresh";
    private static final String CSRF = "__Host-csrf";
    private static final String STATE = "__Host-oauth-state";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthSessionService sessions;
    @Autowired OAuthLoginStateService states;
    @Autowired AuthCleanupService cleanup;
    @Autowired TestClock clock;
    @LocalServerPort int port;
    @MockitoBean KakaoClient kakao;

    @BeforeEach
    void reset() {
        clock.set(NOW);
        jdbc.execute("TRUNCATE app.oauth_login_requests, app.auth_tokens, app.auth_sessions, app.accounts CASCADE");
        when(kakao.authorizationUri(anyString())).thenAnswer(invocation ->
                "https://kauth.kakao.com/oauth/authorize?state=" + invocation.getArgument(0));
        when(kakao.fetchSubject("valid-code")).thenReturn("123456");
    }

    @Test
    void loginRefreshMeAndLogoutWithoutRefreshCookieFollowTheContract() throws Exception {
        var login = loginChallenge();
        var callback = mvc.perform(get("/api/auth/callback/kakao").secure(true)
                .param("state", login.state()).param("code", "valid-code").cookie(login.nonce()))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertThat(callback.getHeader(HttpHeaders.LOCATION)).isEqualTo("https://podo.test/auth/success");
        assertThat(callback.getHeaders(HttpHeaders.SET_COOKIE)).anySatisfy(value ->
                assertThat(value).startsWith(REFRESH + "=").contains("HttpOnly", "Secure", "Path=/", "SameSite=Lax"));
        assertThat(callback.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(value -> value.contains("Domain="));
        String refresh = cookie(callback, REFRESH).getValue();
        assertThat(refresh).matches("[A-Za-z0-9_-]{43}");
        var pair = issue(refresh);
        String access = json(pair).get("accessToken").asText();
        assertThat(json(pair).get("expiresIn").asInt()).isEqualTo(1800);
        assertThat(json(pair).get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(pair.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(pair.getHeader("X-Request-Id")).isNotBlank();
        var me = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json(me).get("accountId").asText()).isEqualTo(json(pair).get("accountId").asText());
        var csrf = csrfWithAccess(access);
        var logout = mvc.perform(post("/api/auth/logout").header(HttpHeaders.ORIGIN, "https://podo.test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .header("X-CSRF-Token", csrf.getValue()).cookie(csrf))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(logout.getContentAsString()).isEmpty();
        assertThat(logout.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2).allSatisfy(value ->
                assertThat(value).contains("Max-Age=0", "HttpOnly", "Secure", "Path=/"));
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_tokens WHERE revoked_at IS NULL", Integer.class))
                .isZero();
        assertThat(jdbc.queryForList("SELECT token_hash FROM app.auth_tokens", String.class))
                .allMatch(hash -> hash.matches("[a-f0-9]{64}")).doesNotContain(access, refresh);
    }

    @Test
    void invalidBearerIsRejectedEvenOnPublicEndpointsAndContextsCannotMix() throws Exception {
        mvc.perform(get("/api/auth/csrf").header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        var a = sessions.issueForKakaoSubject("a");
        var access = sessions.rotateRefresh(a.refreshToken());
        var b = sessions.issueForKakaoSubject("b");
        var mismatch = mvc.perform(get("/api/auth/csrf")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access.accessToken())
                .cookie(new Cookie(REFRESH, b.refreshToken())))
                .andExpect(status().isConflict()).andReturn().getResponse();
        assertThat(json(mismatch).get("error").get("code").asText()).isEqualTo("AUTH_CONTEXT_MISMATCH");
        var csrf = csrfWithAccess(access.accessToken());
        mvc.perform(post("/api/auth/logout").header(HttpHeaders.ORIGIN, "https://podo.test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access.accessToken())
                .header("X-CSRF-Token", csrf.getValue()).cookie(csrf, new Cookie(REFRESH, b.refreshToken())))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_sessions WHERE revoked_at IS NOT NULL", Integer.class))
                .isZero();
    }

    @Test
    void csrfAndOriginFailuresDoNotConsumeTheRefreshToken() throws Exception {
        var login = sessions.issueForKakaoSubject("csrf");
        var anonymous = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var anonCookie = cookie(anonymous, CSRF);
        mvc.perform(post("/api/auth/token").header(HttpHeaders.ORIGIN, "https://podo.test")
                .header("X-CSRF-Token", anonCookie.getValue()).cookie(anonCookie, new Cookie(REFRESH, login.refreshToken())))
                .andExpect(status().isForbidden());
        var csrf = csrfWithRefresh(login.refreshToken());
        mvc.perform(post("/api/auth/token").header(HttpHeaders.ORIGIN, "https://other.test")
                .header("X-CSRF-Token", csrf.getValue()).cookie(csrf, new Cookie(REFRESH, login.refreshToken())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/token").header(HttpHeaders.ORIGIN, "https://podo.test")
                .cookie(csrf, new Cookie(REFRESH, login.refreshToken())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/token").header(HttpHeaders.ORIGIN, "https://podo.test")
                .header("X-CSRF-Token", csrf.getValue()).cookie(csrf, new Cookie(REFRESH, login.refreshToken()))
                .content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_tokens WHERE consumed_at IS NOT NULL", Integer.class))
                .isZero();
    }

    @Test
    void unexpectedInputsAreRejectedBeforeRotatingOrRevokingTokens() throws Exception {
        mvc.perform(get("/api/auth/csrf").param("unexpected", "x")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/login/kakao").param("returnUrl", "https://other.test"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/csrf").content("{}")) .andExpect(status().isBadRequest());
        var login = sessions.issueForKakaoSubject("strict-input");
        var pair = sessions.rotateRefresh(login.refreshToken());
        var csrf = csrfWithAccess(pair.accessToken());
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + pair.accessToken())
                .param("accountId", "ignored")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/token").param("unexpected", "x")
                .header(HttpHeaders.ORIGIN, "https://podo.test").header("X-CSRF-Token", csrf.getValue())
                .cookie(csrf, new Cookie(REFRESH, pair.refreshToken())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/logout").param("unexpected", "x")
                .header(HttpHeaders.ORIGIN, "https://podo.test").header("X-CSRF-Token", csrf.getValue())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + pair.accessToken()).cookie(csrf))
                .andExpect(status().isBadRequest());
        assertThat(sessions.authenticateRefresh(pair.refreshToken()).sessionId()).isEqualTo(login.sessionId());
        assertThat(sessions.authenticateAccess(pair.accessToken()).sessionId()).isEqualTo(login.sessionId());
    }

    @Test
    void servletFormParsingCannotHideANonemptyTokenRequestBody() throws Exception {
        var login = sessions.issueForKakaoSubject("form-body");
        var csrf = csrfWithRefresh(login.refreshToken());
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/token"))
                    .timeout(Duration.ofSeconds(5))
                    .header(HttpHeaders.ORIGIN, "https://podo.test")
                    .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .header("X-CSRF-Token", csrf.getValue())
                    .header(HttpHeaders.COOKIE, REFRESH + "=" + login.refreshToken() + "; " + CSRF + "=" + csrf.getValue())
                    .POST(HttpRequest.BodyPublishers.ofString("&"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
        }
        assertThat(sessions.authenticateRefresh(login.refreshToken()).sessionId()).isEqualTo(login.sessionId());
    }

    @Test
    void callbackRequiresTheBrowserNonceAndConsumesStateDespiteProviderFailure() throws Exception {
        var challenge = loginChallenge();
        mvc.perform(get("/api/auth/callback/kakao").param("state", challenge.state()).param("code", "valid-code")
                .cookie(new Cookie(STATE, "wrong"))).andExpect(status().isFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.oauth_login_requests WHERE consumed_at IS NOT NULL", Integer.class))
                .isZero();
        when(kakao.fetchSubject("bad-code")).thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        for (int i = 0; i < 2; i++) {
            var response = mvc.perform(get("/api/auth/callback/kakao").param("state", challenge.state())
                    .param("code", "bad-code").cookie(challenge.nonce()))
                    .andExpect(status().isFound()).andReturn().getResponse();
            assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo("https://podo.test/auth/error");
        }
        verify(kakao, times(1)).fetchSubject("bad-code");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_sessions", Integer.class)).isZero();
    }

    @Test
    void callbackRejectsAmbiguousAndUnexpectedParametersWithoutCallingKakao() throws Exception {
        var challenge = loginChallenge();
        mvc.perform(get("/api/auth/callback/kakao").param("state", challenge.state())
                .param("code", "valid-code").param("error", "access_denied").cookie(challenge.nonce()))
                .andExpect(status().isFound());
        mvc.perform(get("/api/auth/callback/kakao").param("state", challenge.state())
                .param("code", "valid-code").param("returnUrl", "https://other.test").cookie(challenge.nonce()))
                .andExpect(status().isFound());
        verify(kakao, times(0)).fetchSubject(anyString());
    }

    @Test
    void refreshRotatesWithoutExtendingSessionOrRevokingEarlierAccess() throws Exception {
        var login = sessions.issueForKakaoSubject("rotation");
        var first = issue(login.refreshToken());
        String firstAccess = json(first).get("accessToken").asText();
        clock.set(NOW.plusSeconds(60));
        issue(cookie(first, REFRESH).getValue());
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstAccess))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/csrf").cookie(new Cookie(REFRESH, login.refreshToken())))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT expires_at FROM app.auth_sessions WHERE id = ?", java.time.OffsetDateTime.class,
                login.sessionId()).toInstant()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        clock.set(NOW.plus(Duration.ofMinutes(31)));
        var expired = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstAccess))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(json(expired).get("error").get("code").asText()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void concurrentFirstLoginsCreateOneAccountAndDistinctSessions() throws Exception {
        var results = race(() -> sessions.issueForKakaoSubject("concurrent"),
                () -> sessions.issueForKakaoSubject("concurrent"));
        assertThat(results.getFirst().accountId()).isEqualTo(results.getLast().accountId());
        assertThat(results.getFirst().sessionId()).isNotEqualTo(results.getLast().sessionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.accounts", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentRefreshRequestsHaveExactlyOneWinner() throws Exception {
        var login = sessions.issueForKakaoSubject("concurrent-refresh");
        Callable<Object> rotate = () -> {
            try { return sessions.rotateRefresh(login.refreshToken()); }
            catch (BusinessException ex) { return ex.getErrorCode(); }
        };
        var results = race(rotate, rotate);
        assertThat(results.stream().filter(AuthSessionService.TokenPair.class::isInstance).count()).isEqualTo(1);
        assertThat(results).contains(ErrorCode.REFRESH_TOKEN_INVALID);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_tokens", Integer.class)).isEqualTo(3);
    }

    @Test
    void logoutAndRefreshRaceCannotLeaveUsableTokens() throws Exception {
        var login = sessions.issueForKakaoSubject("logout-race");
        race(() -> {
            try { sessions.rotateRefresh(login.refreshToken()); }
            catch (BusinessException ex) { assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID); }
            return true;
        }, () -> { sessions.revokeSession(login.sessionId()); return true; });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_tokens WHERE revoked_at IS NULL", Integer.class))
                .isZero();
        assertThrows(BusinessException.class, () -> sessions.authenticateRefresh(login.refreshToken()));
    }

    @Test
    void cleanupPreservesAccountsAndOnlyDeletesRetiredSessionsAfter24Hours() {
        var retired = sessions.issueForKakaoSubject("retired");
        sessions.revokeSession(retired.sessionId());
        states.issue();
        clock.set(NOW.plus(Duration.ofHours(23)));
        cleanup.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_sessions", Integer.class)).isEqualTo(1);
        clock.set(NOW.plus(Duration.ofHours(25)));
        sessions.issueForKakaoSubject("still-active");
        cleanup.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.accounts", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_sessions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_tokens", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.oauth_login_requests", Integer.class)).isZero();
    }

    private Challenge loginChallenge() throws Exception {
        var response = mvc.perform(get("/api/auth/login/kakao")).andExpect(status().isFound()).andReturn().getResponse();
        String state = UriComponentsBuilder.fromUriString(response.getHeader(HttpHeaders.LOCATION))
                .build().getQueryParams().getFirst("state");
        return new Challenge(state, cookie(response, STATE));
    }

    private MockHttpServletResponse issue(String refresh) throws Exception {
        var csrf = csrfWithRefresh(refresh);
        return mvc.perform(post("/api/auth/token").header(HttpHeaders.ORIGIN, "https://podo.test")
                .header("X-CSRF-Token", csrf.getValue()).cookie(csrf, new Cookie(REFRESH, refresh)))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    private Cookie csrfWithRefresh(String refresh) throws Exception {
        return cookie(mvc.perform(get("/api/auth/csrf").cookie(new Cookie(REFRESH, refresh)))
                .andExpect(status().isOk()).andReturn().getResponse(), CSRF);
    }

    private Cookie csrfWithAccess(String access) throws Exception {
        return cookie(mvc.perform(get("/api/auth/csrf").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk()).andReturn().getResponse(), CSRF);
    }

    private Cookie cookie(MockHttpServletResponse response, String name) {
        String header = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString());
    }

    private <T> List<T> race(Callable<T> left, Callable<T> right) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return left.call(); });
            var b = executor.submit(() -> { start.await(); return right.call(); });
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }

    private record Challenge(String state, Cookie nonce) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(NOW);
        void set(Instant now) { instant.set(now); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
