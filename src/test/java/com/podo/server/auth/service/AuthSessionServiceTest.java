package com.podo.server.auth.service;

import com.podo.server.auth.domain.Account;
import com.podo.server.auth.domain.AuthSession;
import com.podo.server.auth.domain.AuthToken;
import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.auth.repository.AuthSessionRepository;
import com.podo.server.auth.repository.AuthTokenRepository;
import com.podo.server.auth.support.OpaqueTokens;
import com.podo.server.auth.withdrawal.WithdrawalRepository;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    private final AuthTokenRepository tokens = mock(AuthTokenRepository.class);
    private final UUID accountId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final String rawRefresh = OpaqueTokens.newToken();
    private final String refreshHash = OpaqueTokens.hash(rawRefresh);
    private final AuthSessionService service = new AuthSessionService(
            accounts, sessions, tokens, Clock.fixed(NOW, ZoneOffset.UTC), mock(WithdrawalRepository.class));

    @BeforeEach
    void activeAccount() {
        when(accounts.findById(accountId)).thenReturn(Optional.of(new Account(accountId, "kakao-subject", "ACTIVE")));
        when(accounts.findLockedById(accountId)).thenReturn(Optional.of(new Account(accountId, "kakao-subject", "ACTIVE")));
        when(sessions.findAccountIdById(sessionId)).thenReturn(Optional.of(accountId));
    }

    @Test
    void rotationConsumesRefreshAndIssues30MinuteAccessWithoutExtendingSession() {
        Instant absoluteExpiry = NOW.plus(Duration.ofDays(14));
        AuthToken refresh = refreshToken(absoluteExpiry);
        when(tokens.findSessionIdByTokenHash(refreshHash)).thenReturn(Optional.of(sessionId));
        when(sessions.findLockedById(sessionId)).thenReturn(Optional.of(session(absoluteExpiry)));
        when(tokens.findById(refreshHash)).thenReturn(Optional.of(refresh));

        AuthSessionService.TokenPair result = service.rotateRefresh(rawRefresh);

        assertThat(refresh.getConsumedAt()).isEqualTo(NOW);
        assertThat(result.accountId()).isEqualTo(accountId);
        assertThat(result.sessionId()).isEqualTo(sessionId);
        assertThat(result.expiresAt()).isEqualTo(absoluteExpiry);
        assertThat(result.refreshToken()).isNotEqualTo(rawRefresh);
        ArgumentCaptor<Iterable<AuthToken>> capture = ArgumentCaptor.forClass(Iterable.class);
        verify(tokens).saveAll(capture.capture());
        assertThat(capture.getValue()).extracting(AuthToken::getTokenHash)
                .containsExactlyInAnyOrder(OpaqueTokens.hash(result.accessToken()), OpaqueTokens.hash(result.refreshToken()));
        assertThat(capture.getValue()).filteredOn(token -> token.getKind() == AuthToken.Kind.ACCESS)
                .extracting(AuthToken::getExpiresAt).containsExactly(NOW.plusSeconds(1800));
        assertThat(capture.getValue()).filteredOn(token -> token.getKind() == AuthToken.Kind.REFRESH)
                .extracting(AuthToken::getExpiresAt).containsExactly(absoluteExpiry);
    }

    @Test
    void rotationRejectsConsumedRefreshAndSessionWithLessThan30MinutesRemaining() {
        Instant expiresAt = NOW.plusSeconds(1799);
        AuthToken refresh = refreshToken(expiresAt);
        when(tokens.findSessionIdByTokenHash(refreshHash)).thenReturn(Optional.of(sessionId));
        when(sessions.findLockedById(sessionId)).thenReturn(Optional.of(session(expiresAt)));
        when(tokens.findById(refreshHash)).thenReturn(Optional.of(refresh));

        assertError(() -> service.rotateRefresh(rawRefresh), ErrorCode.REFRESH_TOKEN_INVALID);
        assertThat(refresh.getConsumedAt()).isNull();

        when(sessions.findLockedById(sessionId)).thenReturn(Optional.of(session(NOW.plusSeconds(1800))));
        refresh.consume(NOW.minusSeconds(1));
        assertError(() -> service.rotateRefresh(rawRefresh), ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void exact30MinuteRemainderCanIssueAccess() {
        Instant expiresAt = NOW.plusSeconds(1800);
        when(tokens.findSessionIdByTokenHash(refreshHash)).thenReturn(Optional.of(sessionId));
        when(sessions.findLockedById(sessionId)).thenReturn(Optional.of(session(expiresAt)));
        when(tokens.findById(refreshHash)).thenReturn(Optional.of(refreshToken(expiresAt)));
        assertThat(service.rotateRefresh(rawRefresh).expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void accessExpiryIsDistinctFromRevocationAndInactiveAccount() {
        String rawAccess = OpaqueTokens.newToken();
        String hash = OpaqueTokens.hash(rawAccess);
        AuthToken access = new AuthToken(hash, sessionId, AuthToken.Kind.ACCESS, NOW);
        AuthSession session = session(NOW.plusSeconds(1800));
        when(tokens.findById(hash)).thenReturn(Optional.of(access));
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        assertError(() -> service.authenticateAccess(rawAccess), ErrorCode.TOKEN_EXPIRED);

        session.revoke(NOW);
        assertError(() -> service.authenticateAccess(rawAccess), ErrorCode.INVALID_TOKEN);
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session(NOW.plusSeconds(1800))));
        when(accounts.findById(accountId)).thenReturn(Optional.of(new Account(accountId, "kakao-subject", "DISABLED")));
        assertError(() -> service.authenticateAccess(rawAccess), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void accessRejectsRefreshAndRefreshRejectsAccessAndConsumedTokens() {
        AuthToken refresh = refreshToken(NOW.plusSeconds(1800));
        when(tokens.findById(refreshHash)).thenReturn(Optional.of(refresh));
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session(NOW.plusSeconds(1800))));
        assertError(() -> service.authenticateAccess(rawRefresh), ErrorCode.INVALID_TOKEN);
        assertThat(service.authenticateRefresh(rawRefresh)).isEqualTo(new AuthenticatedAccount(accountId, sessionId));
        refresh.consume(NOW);
        assertError(() -> service.authenticateRefresh(rawRefresh), ErrorCode.REFRESH_TOKEN_INVALID);

        when(tokens.findById(refreshHash)).thenReturn(Optional.of(
                new AuthToken(refreshHash, sessionId, AuthToken.Kind.ACCESS, NOW.plusSeconds(1800))));
        assertError(() -> service.authenticateRefresh(rawRefresh), ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void malformedTokensAreRejectedWithTheirPublicError() {
        assertError(() -> service.authenticateAccess(null), ErrorCode.INVALID_TOKEN);
        assertError(() -> service.rotateRefresh("malformed"), ErrorCode.REFRESH_TOKEN_INVALID);
        assertError(() -> service.authenticateRefresh("malformed"), ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void repeatedKakaoLoginCreatesSeparate14DaySessionsWithRefreshOnly() {
        when(accounts.findLockedByKakaoSubject("kakao-subject"))
                .thenReturn(Optional.of(new Account(accountId, "kakao-subject", "ACTIVE")));
        AuthSessionService.LoginSession first = service.issueForKakaoSubject("kakao-subject");
        AuthSessionService.LoginSession second = service.issueForKakaoSubject("kakao-subject");

        assertThat(first.accountId()).isEqualTo(accountId);
        assertThat(second.accountId()).isEqualTo(accountId);
        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        assertThat(first.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        ArgumentCaptor<AuthToken> capture = ArgumentCaptor.forClass(AuthToken.class);
        verify(tokens, times(2)).save(capture.capture());
        assertThat(capture.getAllValues()).extracting(AuthToken::getKind)
                .containsOnly(AuthToken.Kind.REFRESH);
        assertThat(capture.getAllValues()).extracting(AuthToken::getTokenHash)
                .containsExactly(OpaqueTokens.hash(first.refreshToken()), OpaqueTokens.hash(second.refreshToken()));
    }

    @Test
    void logoutRevokesCurrentSessionAndAllItsTokens() {
        AuthSession session = session(NOW.plusSeconds(1800));
        when(sessions.findLockedById(sessionId)).thenReturn(Optional.of(session));

        service.revokeSession(sessionId);

        assertThat(session.getRevokedAt()).isEqualTo(NOW);
        verify(tokens).revokeForSession(sessionId, NOW);
        assertError(() -> service.revokeSession(sessionId), ErrorCode.INVALID_TOKEN);
    }

    private AuthSession session(Instant expiresAt) {
        return new AuthSession(sessionId, accountId, NOW.minusSeconds(10), expiresAt);
    }

    private AuthToken refreshToken(Instant expiresAt) {
        return new AuthToken(refreshHash, sessionId, AuthToken.Kind.REFRESH, expiresAt);
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
