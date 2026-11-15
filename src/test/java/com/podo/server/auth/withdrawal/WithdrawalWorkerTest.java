package com.podo.server.auth.withdrawal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.podo.server.auth.withdrawal.WithdrawalRepository.Stage.*;
import static org.mockito.Mockito.*;

class WithdrawalWorkerTest {
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private final WithdrawalRepository jobs = mock(WithdrawalRepository.class);
    private final KakaoUnlinkClient unlink = mock(KakaoUnlinkClient.class);
    private final WithdrawalCleanup cleanup = mock(WithdrawalCleanup.class);
    private final WithdrawalRepository.Claim claim = new WithdrawalRepository.Claim(UUID.randomUUID(), UUID.randomUUID());

    @Test
    void disabledWorkerDoesNotClaimOrReadInvalidTiming() {
        worker().runOnce();
        verifyNoInteractions(jobs, cleanup);
    }

    @Test
    void confirmedUnlinkPrecedesPersonalCleanupAndFinish() {
        ready(1);
        when(jobs.stage(claim, NOW)).thenReturn(Optional.of(UNLINK_PENDING), Optional.of(CLEANUP_PENDING), Optional.of(FILES_PENDING));
        when(jobs.subject(claim, NOW)).thenReturn(Optional.of("123456"));
        when(unlink.unlink("123456")).thenReturn(UnlinkResult.CONFIRMED);
        when(jobs.advance(claim, UNLINK_PENDING, CLEANUP_PENDING, NOW)).thenReturn(true);
        when(cleanup.deleteFiles(claim.accountId(), claim.leaseToken())).thenReturn(true);
        worker().runOnce();
        var order = inOrder(unlink, jobs, cleanup);
        order.verify(unlink).unlink("123456");
        order.verify(jobs).advance(claim, UNLINK_PENDING, CLEANUP_PENDING, NOW);
        order.verify(cleanup).prepare(claim.accountId(), claim.leaseToken());
        order.verify(cleanup).deleteFiles(claim.accountId(), claim.leaseToken());
        order.verify(cleanup).finish(claim.accountId(), claim.leaseToken());
    }

    @Test
    void retriesAllClassifiedFailuresWithoutAttemptLimitAndCapsDelay() {
        for (long attempt : new long[]{1, 2, 7, 100000}) {
            reset(jobs, unlink, cleanup);
            ready(attempt);
            when(jobs.stage(claim, NOW)).thenReturn(Optional.of(UNLINK_PENDING));
            when(jobs.subject(claim, NOW)).thenReturn(Optional.of("123456"));
            var failure = mock(KakaoUnlinkClient.UnlinkFailure.class);
            when(failure.code()).thenReturn(KakaoUnlinkClient.FailureCode.AUTHENTICATION);
            when(unlink.unlink("123456")).thenThrow(failure);
            worker().runOnce();
            long delay = attempt == 1 ? 30 : attempt == 2 ? 60 : 1800;
            verify(jobs).retry(claim, "AUTHENTICATION", NOW.plusSeconds(delay), NOW);
            verifyNoInteractions(cleanup);
        }
    }

    @Test
    void lostLeaseCannotAdvanceOrFinishAndOwnedCleanupFailureIsRetained() {
        ready(4);
        when(jobs.stage(claim, NOW)).thenReturn(Optional.of(FILES_PENDING));
        when(cleanup.deleteFiles(claim.accountId(), claim.leaseToken())).thenReturn(false);
        worker().runOnce();
        verify(jobs).retry(claim, "CLEANUP_FAILED", NOW.plusSeconds(240), NOW);
        verify(cleanup, never()).finish(any(), any());
        reset(jobs, unlink, cleanup);
        ready(4);
        when(jobs.stage(claim, NOW)).thenReturn(Optional.of(FILES_PENDING), Optional.empty());
        when(cleanup.deleteFiles(claim.accountId(), claim.leaseToken())).thenThrow(new IllegalStateException("private cause"));
        worker().runOnce();
        verify(jobs, never()).retry(any(), any(), any(), any());
    }

    private void ready(long attempts) {
        when(unlink.ready()).thenReturn(true);
        when(jobs.claim(NOW)).thenReturn(Optional.of(claim));
        when(jobs.attemptCount(claim, NOW)).thenReturn(Optional.of(attempts));
    }

    private WithdrawalWorker worker() {
        return new WithdrawalWorker(jobs, unlink, cleanup, Clock.fixed(NOW, ZoneOffset.UTC),
                new WithdrawalProperties("", java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(15),
                        java.time.Duration.ofMinutes(5), java.time.Duration.ofSeconds(5)));
    }
}
