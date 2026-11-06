package com.podo.server.auth.service;

import com.podo.server.auth.repository.AuthSessionRepository;
import com.podo.server.auth.repository.AuthTokenRepository;
import com.podo.server.auth.repository.OAuthLoginRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthCleanupService {

    private final AuthTokenRepository tokens;
    private final AuthSessionRepository sessions;
    private final OAuthLoginRequestRepository requests;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    @Transactional
    public void cleanup() {
        Instant cutoff = clock.instant().minus(Duration.ofHours(24));
        tokens.deleteForRetiredSessions(cutoff);
        sessions.deleteRetiredBefore(cutoff);
        requests.deleteExpiredBefore(cutoff);
    }
}
