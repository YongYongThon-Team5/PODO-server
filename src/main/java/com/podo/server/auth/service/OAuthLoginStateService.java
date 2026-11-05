package com.podo.server.auth.service;

import com.podo.server.auth.domain.OAuthLoginRequest;
import com.podo.server.auth.repository.OAuthLoginRequestRepository;
import com.podo.server.auth.support.OpaqueTokens;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OAuthLoginStateService {

    private final OAuthLoginRequestRepository requests;
    private final Clock clock;

    @Transactional
    public LoginChallenge issue() {
        String state = OpaqueTokens.newToken();
        String nonce = OpaqueTokens.newToken();
        requests.save(new OAuthLoginRequest(OpaqueTokens.hash(state), OpaqueTokens.hash(nonce),
                clock.instant().plus(Duration.ofMinutes(5))));
        return new LoginChallenge(state, nonce);
    }

    // A later provider/network failure must not restore this one-time challenge.


    private OAuthLoginRequest lockedRequest(String state) {
        return requests.findLockedByStateHash(OpaqueTokens.hash(state))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
    }

    public record LoginChallenge(String state, String browserNonce) {
    }
}
