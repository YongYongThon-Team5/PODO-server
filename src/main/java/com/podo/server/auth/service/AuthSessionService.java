package com.podo.server.auth.service;

import com.podo.server.auth.domain.Account;
import com.podo.server.auth.domain.AuthSession;
import com.podo.server.auth.domain.AuthToken;
import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.auth.repository.AuthSessionRepository;
import com.podo.server.auth.repository.AuthTokenRepository;
import com.podo.server.auth.support.OpaqueTokens;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthSessionService {

    private static final Duration SESSION_LIFETIME = Duration.ofDays(14);
    private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(30);

    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthTokenRepository tokens;
    private final Clock clock;

    @Transactional
    public LoginSession issueForKakaoSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Instant now = clock.instant();
        // PostgreSQL arbitrates concurrent first logins without aborting this transaction.
        accounts.insertIfAbsent(UUID.randomUUID(), subject, now);
        Account account = accounts.findByKakaoSubject(subject)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        requireActiveAccount(account.getId(), ErrorCode.INVALID_TOKEN);

        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = now.plus(SESSION_LIFETIME);
        sessions.save(new AuthSession(sessionId, account.getId(), now, expiresAt));
        String refresh = OpaqueTokens.newToken();
        tokens.save(new AuthToken(OpaqueTokens.hash(refresh), sessionId, AuthToken.Kind.REFRESH, expiresAt));
        return new LoginSession(account.getId(), sessionId, refresh, expiresAt);
    }

    @Transactional(readOnly = true)
    public AuthenticatedAccount authenticateAccess(String rawAccess) {
        String hash = checkedHash(rawAccess, ErrorCode.INVALID_TOKEN);
        AuthToken token = tokens.findById(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        if (token.getKind() != AuthToken.Kind.ACCESS || token.getRevokedAt() != null || token.getConsumedAt() != null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        Instant now = clock.instant();
        AuthSession session = sessions.findById(token.getSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        requireActiveSession(session, now, ErrorCode.INVALID_TOKEN);
        requireActiveAccount(session.getAccountId(), ErrorCode.INVALID_TOKEN);
        if (!token.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }
        return new AuthenticatedAccount(session.getAccountId(), session.getId());
    }

    @Transactional(readOnly = true)
    public AuthenticatedAccount authenticateRefresh(String rawRefresh) {
        String hash = checkedHash(rawRefresh, ErrorCode.REFRESH_TOKEN_INVALID);
        AuthToken token = tokens.findById(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        Instant now = clock.instant();
        requireUsableRefresh(token, now);
        AuthSession session = sessions.findById(token.getSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        requireActiveSession(session, now, ErrorCode.REFRESH_TOKEN_INVALID);
        requireActiveAccount(session.getAccountId(), ErrorCode.REFRESH_TOKEN_INVALID);
        return new AuthenticatedAccount(session.getAccountId(), session.getId());
    }



    private static String checkedHash(String raw, ErrorCode error) {
        if (!OpaqueTokens.isToken(raw)) {
            throw new BusinessException(error);
        }
        return OpaqueTokens.hash(raw);
    }

    private static void requireUsableRefresh(AuthToken token, Instant now) {
        if (token.getKind() != AuthToken.Kind.REFRESH || token.getConsumedAt() != null
                || token.getRevokedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    private static void requireActiveSession(AuthSession session, Instant now, ErrorCode error) {
        if (!session.isActiveAt(now)) {
            throw new BusinessException(error);
        }
    }

    private void requireActiveAccount(UUID accountId, ErrorCode error) {
        if (accounts.findById(accountId).filter(Account::isActive).isEmpty()) {
            throw new BusinessException(error);
        }
    }

    public record LoginSession(UUID accountId, UUID sessionId, String refreshToken, Instant expiresAt) {
    }

    public record TokenPair(UUID accountId, UUID sessionId, String accessToken, String refreshToken, Instant expiresAt) {
    }
}
