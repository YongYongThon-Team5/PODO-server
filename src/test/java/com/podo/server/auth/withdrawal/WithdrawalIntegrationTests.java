package com.podo.server.auth.withdrawal;

import com.podo.server.auth.csrf.CsrfService;
import com.podo.server.auth.kakao.KakaoClient;
import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.auth.repository.AuthSessionRepository;
import com.podo.server.auth.repository.AuthTokenRepository;
import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.auth.service.OAuthLoginStateService;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.podo.server.auth.withdrawal.WithdrawalRepository.Stage.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "podo.auth.withdrawal.worker-enabled=false",
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@Testcontainers
class WithdrawalIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired CsrfService csrf;
    @MockitoBean KakaoUnlinkClient unlink;
    @MockitoBean KakaoClient kakao;
    @Autowired OAuthLoginStateService states;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
    @Autowired AuthSessionService sessions;
    @Autowired AuthTokenRepository tokenRepository;
    @Autowired AuthSessionRepository sessionRepository;
    @MockitoSpyBean WithdrawalRepository jobs;
    @Autowired WithdrawalService withdrawal;
    @Autowired WithdrawalWorker worker;
    @Autowired Clock clock;
    @Autowired WithdrawalCleanup cleanup;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE app.accounts CASCADE");
        when(unlink.ready()).thenReturn(true);
    }

    @Test
    void deleteAcceptsAtomicallyClearsCookiesAndRevokesEveryDevice() throws Exception {
        var login = sessions.issueForKakaoSubject("123456");
        var second = sessions.issueForKakaoSubject("123456");
        var pair = sessions.rotateRefresh(login.refreshToken());
        var response = mvc.perform(withdrawRequest(pair.accessToken(), login.sessionId()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"PROCESSING\"}");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2).allSatisfy(cookie ->
                assertThat(cookie).contains("Max-Age=0", "Secure", "HttpOnly", "Path=/"));
        assertThat(accounts.findById(login.accountId()).orElseThrow().isWithdrawing()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.account_withdrawals", Integer.class)).isEqualTo(1);
        assertError(() -> sessions.authenticateAccess(pair.accessToken()), ErrorCode.INVALID_TOKEN);
        assertError(() -> sessions.authenticateRefresh(second.refreshToken()), ErrorCode.REFRESH_TOKEN_INVALID);
        mvc.perform(withdrawRequest(pair.accessToken(), login.sessionId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteRejectsInvalidBoundariesAndMissingConfigurationWithoutMutation() throws Exception {
        var login = sessions.issueForKakaoSubject("123456");
        var pair = sessions.rotateRefresh(login.refreshToken());
        mvc.perform(delete("/api/me"))
                .andExpect(status().isUnauthorized());
        mvc.perform(withdrawRequest(pair.accessToken(), login.sessionId()).header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden());
        mvc.perform(withdrawRequest(pair.accessToken(), login.sessionId()).header("X-CSRF-Token", "invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(withdrawRequest(pair.accessToken(), login.sessionId()).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(withdrawRequest(pair.accessToken(), login.sessionId()).param("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        var other = sessions.issueForKakaoSubject("987654");
        mvc.perform(withdrawRequest(pair.accessToken(), login.sessionId())
                .cookie(new Cookie("__Host-refresh", other.refreshToken())))
                .andExpect(status().isConflict());
        when(unlink.ready()).thenReturn(false);
        var response = mvc.perform(withdrawRequest(pair.accessToken(), login.sessionId()))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("SERVICE_UNAVAILABLE").doesNotContain("123456");
        assertThat(accounts.findById(login.accountId()).orElseThrow().isActive()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.account_withdrawals", Integer.class)).isZero();
        assertThat(sessions.authenticateAccess(pair.accessToken()).accountId()).isEqualTo(login.accountId());
    }

    @Test
    void validatedBlockedCallbackRearmsUnlinkAndInvalidatesTheOldLease() throws Exception {
        var login = sessions.issueForKakaoSubject("123456");
        withdraw(login.accountId());
        var old = jobs.claim(Instant.now()).orElseThrow();
        assertThat(jobs.advance(old, UNLINK_PENDING, CLEANUP_PENDING, Instant.now())).isTrue();
        assertThat(jobs.advance(old, CLEANUP_PENDING, FILES_PENDING, Instant.now())).isTrue();
        var challenge = states.issue();
        when(kakao.fetchSubject("valid-code")).thenReturn("123456");
        var response = mvc.perform(get("/api/auth/callback/kakao")
                .param("state", challenge.state()).param("code", "valid-code")
                .cookie(new Cookie("__Host-oauth-state", challenge.browserNonce())))
                .andExpect(status().isFound())
                .andReturn().getResponse();
        assertThat(response.getHeader("Location")).isEqualTo("https://podo.test/auth/error?error=ACCOUNT_WITHDRAWING");
        assertThat(jobs.stage(old, Instant.now())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT stage FROM app.account_withdrawals WHERE account_id = ?", String.class, login.accountId()))
                .isEqualTo("UNLINK_PENDING");
        assertNoUsableSessionsOrTokens();
        assertThatThrownBy(() -> cleanup.finish(login.accountId(), old.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.findById(login.accountId())).isPresent();
    }

    @Test
    void unavailableAndUntrustedCallbacksUseOnlyConfiguredFailureUri() throws Exception {
        var challenge = states.issue();
        when(kakao.fetchSubject("unavailable")).thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        var response = mvc.perform(get("/api/auth/callback/kakao")
                .param("state", challenge.state()).param("code", "unavailable")
                .cookie(new Cookie("__Host-oauth-state", challenge.browserNonce())))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertThat(response.getHeader("Location")).isEqualTo("https://podo.test/auth/error");
        var untrusted = mvc.perform(get("/api/auth/callback/kakao")
                .param("redirect_uri", "https://evil.test").param("error", "ACCOUNT_WITHDRAWING"))
                .andExpect(status().isFound()).andReturn().getResponse();
        assertThat(untrusted.getHeader("Location")).isEqualTo("https://podo.test/auth/error");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.accounts", Integer.class)).isZero();
    }

    private MockHttpServletRequestBuilder withdrawRequest(String access, UUID session) {
        String value = csrf.issue(session);
        return delete("/api/me")
                .header("Authorization", "Bearer " + access).header("Origin", "https://podo.test")
                .header("X-CSRF-Token", value).cookie(new Cookie("__Host-csrf", value));
    }

    @Test
    void acceptanceDatabaseFailureRollsBackTheActualService() {
        var login = sessions.issueForKakaoSubject("123456");
        var pair = sessions.rotateRefresh(login.refreshToken());
        doThrow(new IllegalStateException("simulated failure"))
                .when(jobs).enqueue(eq(login.accountId()), any());
        assertThatThrownBy(() -> withdrawal.request(login.accountId())).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(accounts.findById(login.accountId()).orElseThrow().isActive()).isTrue();
        assertThat(sessions.authenticateAccess(pair.accessToken()).accountId()).isEqualTo(login.accountId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.account_withdrawals", Integer.class)).isZero();
    }

    @Test
    void providerSuccessBeforeLostDatabaseRecordRetriesAfterWorkerRestartWithoutAmbientTransaction() {
        var login = sessions.issueForKakaoSubject("123456");
        withdrawal.request(login.accountId());
        when(unlink.unlink("123456")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return UnlinkResult.CONFIRMED;
        });
        doThrow(new IllegalStateException("lost write"))
                .doCallRealMethod().when(jobs).advance(any(),
                        eq(UNLINK_PENDING), eq(CLEANUP_PENDING),
                        any());
        tx().executeWithoutResult(status -> worker.runOnce());
        assertThat(jdbc.queryForObject("SELECT stage FROM app.account_withdrawals", String.class)).isEqualTo("UNLINK_PENDING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM app.account_withdrawals", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT error_code FROM app.account_withdrawals", String.class)).isEqualTo("CLEANUP_FAILED");
        jdbc.update("UPDATE app.account_withdrawals SET retry_at = now() - interval '1 second'");
        new WithdrawalWorker(jobs, unlink, cleanup, clock, new WithdrawalProperties("", "5s", "15s", "5m", "5s")).runOnce();
        verify(unlink, times(2)).unlink("123456");
        assertThat(accounts.findById(login.accountId())).isEmpty();
        assertThat(sessions.issueForKakaoSubject("123456").accountId()).isNotEqualTo(login.accountId());
    }

    @Test
    void callbackReconsentDuringUnlinkPreventsStaleWorkerFinalizing() throws Exception {
        var login = sessions.issueForKakaoSubject("123456");
        withdrawal.request(login.accountId());
        when(unlink.unlink("123456")).thenAnswer(call -> {
            assertError(() -> sessions.issueForKakaoCallbackSubject("123456"), ErrorCode.ACCOUNT_WITHDRAWING);
            return UnlinkResult.CONFIRMED;
        });
        worker.runOnce();
        assertThat(accounts.findById(login.accountId())).isPresent();
        assertThat(jdbc.queryForObject("SELECT stage FROM app.account_withdrawals", String.class)).isEqualTo("UNLINK_PENDING");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM app.account_withdrawals", UUID.class)).isNull();
        when(unlink.unlink("123456")).thenReturn(UnlinkResult.CONFIRMED);
        worker.runOnce();
        assertThat(accounts.findById(login.accountId())).isEmpty();
    }

    @Test
    void withdrawalBlocksAllSessionsAndRejoin() {
        var first = sessions.issueForKakaoSubject("two-devices");
        var second = sessions.issueForKakaoSubject("two-devices");
        var pair = sessions.rotateRefresh(first.refreshToken());
        var other = sessions.issueForKakaoSubject("another-account");
        withdraw(first.accountId());
        withdraw(first.accountId()); // Database acceptance stays unique even when retried internally.

        assertThat(accounts.findById(first.accountId()).orElseThrow().getStatus()).isEqualTo("WITHDRAWING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.account_withdrawals", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_sessions WHERE account_id = ? AND revoked_at IS NULL",
                Integer.class, first.accountId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app.auth_tokens token JOIN app.auth_sessions session ON session.id = token.session_id
                WHERE session.account_id = ? AND token.revoked_at IS NULL
                """, Integer.class, first.accountId())).isZero();
        assertError(() -> sessions.authenticateAccess(pair.accessToken()), ErrorCode.INVALID_TOKEN);
        assertError(() -> sessions.authenticateRefresh(pair.refreshToken()), ErrorCode.REFRESH_TOKEN_INVALID);
        assertError(() -> sessions.rotateRefresh(second.refreshToken()), ErrorCode.REFRESH_TOKEN_INVALID);
        assertError(() -> sessions.issueForKakaoSubject("two-devices"), ErrorCode.ACCOUNT_WITHDRAWING);
        assertThat(sessions.authenticateRefresh(other.refreshToken()).accountId()).isEqualTo(other.accountId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.accounts WHERE kakao_subject = 'two-devices'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void acceptanceFailureRollsBackAccountSessionsTokensAndJobTogether() {
        var login = sessions.issueForKakaoSubject("rollback");
        var pair = sessions.rotateRefresh(login.refreshToken());
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            acceptInTransaction(login.accountId());
            throw new IllegalStateException("simulated transaction failure after durable enqueue");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(accounts.findById(login.accountId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.account_withdrawals", Integer.class)).isZero();
        assertThat(sessions.authenticateAccess(pair.accessToken()).accountId()).isEqualTo(login.accountId());
        assertThat(sessions.authenticateRefresh(pair.refreshToken()).accountId()).isEqualTo(login.accountId());
    }

    @Test
    void concurrentRefreshAndWithdrawalCannotReviveSession() throws Exception {
        var login = sessions.issueForKakaoSubject("withdraw-race");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var withdrawal = executor.submit(() -> tx().executeWithoutResult(status -> {
                accounts.findLockedById(login.accountId()).orElseThrow().beginWithdrawal();
                locked.countDown();
                await(release);
                sessions.revokeAllForAccount(login.accountId());
                jobs.enqueue(login.accountId(), NOW);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var started = new CountDownLatch(1);
            var refresh = executor.submit(() -> {
                started.countDown();
                try { return sessions.rotateRefresh(login.refreshToken()); }
                catch (BusinessException ex) { return ex.getErrorCode(); }
            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThrows(TimeoutException.class, () -> refresh.get(500, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            withdrawal.get(10, TimeUnit.SECONDS);
            assertThat(refresh.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
            assertNoUsableSessionsOrTokens();
        } finally {
            release.countDown();
        }
    }

    @Test
    void refreshCommittingBeforeWithdrawalHasAllNewTokensRevoked() throws Exception {
        var login = sessions.issueForKakaoSubject("refresh-first");
        var rotated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refresh = executor.submit(() -> tx().execute(status -> {
                var pair = sessions.rotateRefresh(login.refreshToken());
                rotated.countDown();
                await(release);
                return pair;
            }));
            assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();
            var withdrawal = executor.submit(() -> withdraw(login.accountId()));
            try {
                assertThrows(TimeoutException.class, () -> withdrawal.get(500, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            var pair = refresh.get(10, TimeUnit.SECONDS);
            withdrawal.get(10, TimeUnit.SECONDS);
            assertError(() -> sessions.authenticateAccess(pair.accessToken()), ErrorCode.INVALID_TOKEN);
            assertError(() -> sessions.authenticateRefresh(pair.refreshToken()), ErrorCode.REFRESH_TOKEN_INVALID);
            assertNoUsableSessionsOrTokens();
        } finally {
            release.countDown();
        }
    }

    @Test
    void loginAndLogoutWaitForWithdrawalAccountLockAndRejectAfterCommit() throws Exception {
        var login = sessions.issueForKakaoSubject("login-logout-race");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var withdrawal = executor.submit(() -> tx().executeWithoutResult(status -> {
                accounts.findLockedById(login.accountId()).orElseThrow().beginWithdrawal();
                locked.countDown();
                await(release);
                sessions.revokeAllForAccount(login.accountId());
                jobs.enqueue(login.accountId(), NOW);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var relogin = executor.submit(() -> errorOf(() -> sessions.issueForKakaoSubject("login-logout-race")));
            var logout = executor.submit(() -> errorOf(() -> sessions.revokeSession(login.sessionId())));
            try {
                assertThrows(TimeoutException.class, () -> relogin.get(500, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> logout.get(500, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            withdrawal.get(10, TimeUnit.SECONDS);
            assertThat(relogin.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.ACCOUNT_WITHDRAWING);
            assertThat(logout.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.INVALID_TOKEN);
            assertNoUsableSessionsOrTokens();
        } finally {
            release.countDown();
        }
    }

    @Test
    void retiredSessionCleanupAndAllSessionRevocationDoNotDeadlock() throws Exception {
        var expired = sessions.issueForKakaoSubject("cleanup-overlap");
        sessions.issueForKakaoSubject("cleanup-overlap"); // The same account still has a live device.
        jdbc.update("UPDATE app.auth_sessions SET expires_at = ? WHERE id = ?",
                NOW.minus(Duration.ofDays(2)).atOffset(java.time.ZoneOffset.UTC), expired.sessionId());
        var tokensDeleted = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var revocationStarted = new CountDownLatch(1);
        var cleanupPid = new AtomicInteger();
        var revocationPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cleanup = executor.submit(() -> tx().executeWithoutResult(status -> {
                cleanupPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                tokenRepository.deleteForRetiredSessions(NOW.minus(Duration.ofHours(24)));
                tokensDeleted.countDown();
                await(releaseCleanup);
                sessionRepository.deleteRetiredBefore(NOW.minus(Duration.ofHours(24)));
            }));
            assertThat(tokensDeleted.await(10, TimeUnit.SECONDS)).isTrue();
            var revocation = executor.submit(() -> tx().executeWithoutResult(status -> {
                revocationPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                revocationStarted.countDown();
                sessions.revokeAllForAccount(expired.accountId());
            }));
            try {
                assertThat(revocationStarted.await(10, TimeUnit.SECONDS)).isTrue();
                // Observe the real PostgreSQL wait edge before allowing cleanup to lock the session.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting;
                do {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class,
                            cleanupPid.get(), revocationPid.get()));
                    if (!waiting) Thread.sleep(20);
                } while (!waiting && System.nanoTime() < deadline);
                assertThat(waiting).as("revocation waits for cleanup's token row lock").isTrue();
            } finally {
                releaseCleanup.countDown();
            }
            cleanup.get(10, TimeUnit.SECONDS);
            revocation.get(10, TimeUnit.SECONDS);
            assertThat(sessionRepository.findById(expired.sessionId())).isEmpty();
            assertNoUsableSessionsOrTokens();
        } finally {
            releaseCleanup.countDown();
        }
    }

    @Test
    void twoWorkersCannotClaimTheSameJob() throws Exception {
        var login = sessions.issueForKakaoSubject("one-job");
        withdraw(login.accountId());
        var results = race(() -> jobs.claim(NOW), () -> jobs.claim(NOW));
        assertThat(results.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM app.account_withdrawals", Long.class)).isEqualTo(1);
    }

    @Test
    void workerSkipsAnotherWorkersLockedJobWithoutWaiting() throws Exception {
        var first = sessions.issueForKakaoSubject("first-job");
        var second = sessions.issueForKakaoSubject("second-job");
        withdraw(first.accountId());
        withdraw(second.accountId());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var worker = executor.submit(() -> tx().execute(status -> {
                var claim = jobs.claim(NOW).orElseThrow();
                locked.countDown();
                await(release);
                return claim;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var other = executor.submit(() -> jobs.claim(NOW).orElseThrow());
            var otherClaim = other.get(5, TimeUnit.SECONDS);
            release.countDown();
            assertThat(worker.get(10, TimeUnit.SECONDS).accountId()).isNotEqualTo(otherClaim.accountId());
        } finally {
            release.countDown();
        }
    }

    @Test
    void expiredLeaseCannotReadAdvanceOrRetryBeforeOrAfterReclaim() {
        var login = sessions.issueForKakaoSubject("expired-job");
        withdraw(login.accountId());
        var old = jobs.claim(NOW).orElseThrow();
        assertThat(jobs.stage(old, NOW)).contains(UNLINK_PENDING);
        assertThat(jobs.claim(NOW.plusSeconds(299))).isEmpty();
        var expiredAt = NOW.plusSeconds(300);
        assertThat(jobs.stage(old, expiredAt)).isEmpty();
        assertThat(jobs.advance(old, UNLINK_PENDING, CLEANUP_PENDING, expiredAt)).isFalse();
        assertThat(jobs.retry(old, "NETWORK", expiredAt.plusSeconds(30), expiredAt)).isFalse();
        var current = jobs.claim(expiredAt).orElseThrow();
        assertThat(current.accountId()).isEqualTo(old.accountId());
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(jobs.advance(old, UNLINK_PENDING, CLEANUP_PENDING, expiredAt)).isFalse();
        assertThat(jobs.retry(old, "NETWORK", expiredAt.plusSeconds(30), expiredAt)).isFalse();
        assertThat(jobs.stage(current, expiredAt)).contains(UNLINK_PENDING);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM app.account_withdrawals", Long.class)).isEqualTo(2);
        assertThat(jobs.advance(current, UNLINK_PENDING, CLEANUP_PENDING, expiredAt)).isTrue();
        assertThat(jobs.advance(current, UNLINK_PENDING, CLEANUP_PENDING, expiredAt)).isFalse();
        assertThat(jobs.advance(current, CLEANUP_PENDING, FILES_PENDING, expiredAt)).isTrue();
        assertThat(jobs.stage(current, expiredAt)).contains(FILES_PENDING);
    }

    @Test
    void retryKeepsStageAndDurableJobUntilDueWithoutAttemptLimit() {
        var login = sessions.issueForKakaoSubject("retry-job");
        withdraw(login.accountId());
        jdbc.update("UPDATE app.account_withdrawals SET attempt_count = 1000000");
        var claim = jobs.claim(NOW).orElseThrow();
        assertThat(jobs.advance(claim, UNLINK_PENDING, CLEANUP_PENDING, NOW)).isTrue();
        assertThat(jobs.retry(claim, "NETWORK", NOW.plusSeconds(30), NOW)).isTrue();
        assertThat(jobs.stage(claim, NOW)).isEmpty();
        assertThat(jobs.claim(NOW.plusSeconds(29))).isEmpty();
        var retry = jobs.claim(NOW.plusSeconds(30)).orElseThrow();
        assertThat(jobs.stage(retry, NOW.plusSeconds(30))).contains(CLEANUP_PENDING);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM app.account_withdrawals", Long.class)).isEqualTo(1000002);
        assertThat(jdbc.queryForObject("SELECT error_code FROM app.account_withdrawals", String.class)).isEqualTo("NETWORK");
        assertThatThrownBy(() -> jobs.advance(retry, CLEANUP_PENDING, UNLINK_PENDING, NOW.plusSeconds(30)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fileQueueIsDurableDeduplicatedAndCannotOutliveItsJob() {
        var login = sessions.issueForKakaoSubject("file-queue");
        withdraw(login.accountId());
        jdbc.update("INSERT INTO app.account_withdrawal_files(account_id, storage_kind, storage_key) VALUES (?, 'PROFILE', 'safe-key')",
                login.accountId());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO app.account_withdrawal_files(account_id, storage_kind, storage_key)
                VALUES (?, 'PROFILE', 'safe-key')
                """, login.accountId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT done FROM app.account_withdrawal_files", Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM app.account_withdrawals WHERE account_id = ?", login.accountId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE app.account_withdrawals SET lease_token = ?", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE app.account_withdrawals SET stage = 'COMPLETE'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void withdraw(UUID accountId) {
        tx().executeWithoutResult(status -> acceptInTransaction(accountId));
    }

    // The ledger assigns request orchestration to Task 4. Exercise its atomic repo sequence here.
    private void acceptInTransaction(UUID accountId) {
        accounts.findLockedById(accountId).orElseThrow().beginWithdrawal();
        sessions.revokeAllForAccount(accountId);
        jobs.enqueue(accountId, NOW);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(manager); }

    private void assertNoUsableSessionsOrTokens() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_sessions WHERE revoked_at IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_tokens WHERE revoked_at IS NULL", Integer.class)).isZero();
    }

    private static ErrorCode errorOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThat(errorOf(action)).isEqualTo(expected);
    }

    private <T> List<T> race(Callable<T> left, Callable<T> right) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { await(start); return left.call(); });
            var b = executor.submit(() -> { await(start); return right.call(); });
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
