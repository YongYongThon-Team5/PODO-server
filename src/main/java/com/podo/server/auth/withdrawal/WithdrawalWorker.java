package com.podo.server.auth.withdrawal;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.podo.server.auth.withdrawal.WithdrawalRepository.Stage.*;

@Service
@RequiredArgsConstructor
public class WithdrawalWorker {
    private static final Logger log = LoggerFactory.getLogger(WithdrawalWorker.class);
    private final WithdrawalRepository jobs;
    private final KakaoUnlinkClient unlink;
    private final WithdrawalCleanup cleanup;
    private final Clock clock;
    private final WithdrawalProperties properties;
    @Value("${podo.auth.withdrawal.worker-enabled:true}")
    private boolean schedulingEnabled = true;
    private Instant nextPoll = Instant.MIN;

    // Fixed safe scheduler: malformed optional settings must disable withdrawal, not application startup.
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public synchronized void poll() {
        if (!schedulingEnabled || !unlink.ready() || clock.instant().isBefore(nextPoll)) return;
        nextPoll = clock.instant().plus(properties.pollInterval());
        runOnce();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runOnce() {
        if (!unlink.ready()) return;
        jobs.claim(clock.instant()).ifPresent(this::process);
    }

    private void process(WithdrawalRepository.Claim claim) {
        try {
            var stage = jobs.stage(claim, clock.instant()).orElse(null);
            if (stage == UNLINK_PENDING) {
                var subject = jobs.subject(claim, clock.instant());
                if (subject.isEmpty()) return;
                if (unlink.unlink(subject.get()) != UnlinkResult.CONFIRMED) {
                    throw new IllegalStateException("Unlink was not confirmed");
                }
                if (!jobs.advance(claim, UNLINK_PENDING, CLEANUP_PENDING, clock.instant())) return;
                stage = jobs.stage(claim, clock.instant()).orElse(null);
            }
            if (stage == CLEANUP_PENDING) {
                cleanup.prepare(claim.accountId(), claim.leaseToken());
                stage = jobs.stage(claim, clock.instant()).orElse(null);
            }
            if (stage == FILES_PENDING) {
                if (!cleanup.deleteFiles(claim.accountId(), claim.leaseToken())) {
                    retry(claim, "CLEANUP_FAILED");
                    return;
                }
                cleanup.finish(claim.accountId(), claim.leaseToken());
            }
        } catch (KakaoUnlinkClient.UnlinkFailure failure) {
            retry(claim, failure.code().name());
        } catch (RuntimeException failure) {
            // Original exceptions may contain storage paths, SQL values or provider data.
            retry(claim, "CLEANUP_FAILED");
        }
    }

    private void retry(WithdrawalRepository.Claim claim, String code) {
        Instant now = clock.instant();
        if (jobs.stage(claim, now).isEmpty()) return;
        jobs.attemptCount(claim, now).ifPresent(attempt -> {
            long seconds = Math.min(1800, 30L << (int) Math.min(6, Math.max(0, attempt - 1)));
            if (jobs.retry(claim, code, now.plusSeconds(seconds), now)) {
                log.warn("Withdrawal retry: code={}, attempt={}", code, attempt);
            }
        });
    }
}
