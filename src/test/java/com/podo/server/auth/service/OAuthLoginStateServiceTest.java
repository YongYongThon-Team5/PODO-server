package com.podo.server.auth.service;

import com.podo.server.auth.domain.OAuthLoginRequest;
import com.podo.server.auth.domain.Account;
import com.podo.server.auth.repository.AccountRepository;
import com.podo.server.auth.repository.OAuthLoginRequestRepository;
import com.podo.server.auth.support.OpaqueTokens;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthLoginStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private final OAuthLoginRequestRepository requests = mock(OAuthLoginRequestRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final OAuthLoginStateService service = new OAuthLoginStateService(requests, accounts, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuesIndependentStateAndNonceAndStoresOnlyHashesFor5Minutes() {
        OAuthLoginStateService.LoginChallenge challenge = service.issue();
        ArgumentCaptor<OAuthLoginRequest> capture = ArgumentCaptor.forClass(OAuthLoginRequest.class);
        verify(requests).save(capture.capture());
        assertThat(OpaqueTokens.isToken(challenge.state())).isTrue();
        assertThat(OpaqueTokens.isToken(challenge.browserNonce())).isTrue();
        assertThat(challenge.state()).isNotEqualTo(challenge.browserNonce());
        assertThat(capture.getValue().getStateHash()).isEqualTo(OpaqueTokens.hash(challenge.state()));
        assertThat(capture.getValue().getBrowserNonceHash()).isEqualTo(OpaqueTokens.hash(challenge.browserNonce()));
        assertThat(capture.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void matchingChallengeCanOnlyBeConsumedOnceAndThenLinkedToAccount() {
        String state = OpaqueTokens.newToken();
        String nonce = OpaqueTokens.newToken();
        OAuthLoginRequest request = request(state, nonce, NOW.plusSeconds(300));
        service.consume(state, nonce);
        assertThat(request.getConsumedAt()).isEqualTo(NOW);
        assertInvalid(() -> service.consume(state, nonce));

        UUID accountId = UUID.randomUUID();
        when(accounts.findLockedById(accountId)).thenReturn(Optional.of(new Account(accountId, "test", "ACTIVE")));
        service.linkAccount(state, accountId);
        assertThat(request.getAccountId()).isEqualTo(accountId);
        assertInvalid(() -> service.linkAccount(state, accountId));
    }

    @Test
    void mismatchedNonceAndExactExpiryAndUnconsumedLinkAreRejected() {
        String state = OpaqueTokens.newToken();
        String nonce = OpaqueTokens.newToken();
        OAuthLoginRequest request = request(state, nonce, NOW.plusSeconds(300));
        assertInvalid(() -> service.consume(state, OpaqueTokens.newToken()));
        assertThat(request.getConsumedAt()).isNull();
        UUID accountId = UUID.randomUUID();
        when(accounts.findLockedById(accountId)).thenReturn(Optional.of(new Account(accountId, "test", "ACTIVE")));
        assertInvalid(() -> service.linkAccount(state, accountId));

        request(state, nonce, NOW);
        assertInvalid(() -> service.consume(state, nonce));
        assertInvalid(() -> service.consume(null, nonce));
        assertInvalid(() -> service.consume(state, null));
    }

    private OAuthLoginRequest request(String state, String nonce, Instant expiresAt) {
        OAuthLoginRequest request = new OAuthLoginRequest(OpaqueTokens.hash(state), OpaqueTokens.hash(nonce), expiresAt);
        when(requests.findLockedByStateHash(OpaqueTokens.hash(state))).thenReturn(Optional.of(request));
        return request;
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
