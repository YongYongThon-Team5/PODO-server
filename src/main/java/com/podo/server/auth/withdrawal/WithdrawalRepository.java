package com.podo.server.auth.withdrawal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WithdrawalRepository {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private final JdbcClient jdbc;

    public boolean enqueue(UUID accountId, Instant now) {
        return jdbc.sql("""
                INSERT INTO app.account_withdrawals (account_id, retry_at)
                VALUES (:account, :now)
                ON CONFLICT (account_id) DO NOTHING
                """)
                .param("account", accountId).param("now", now.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    // One atomic statement keeps the claim transaction short and never holds a lock during HTTP.
    public Optional<Claim> claim(Instant now) {
        UUID token = UUID.randomUUID();
        return jdbc.sql("""
                WITH next_job AS (
                    SELECT account_id FROM app.account_withdrawals
                    WHERE retry_at <= :now
                      AND (lease_token IS NULL OR lease_expires_at <= :now)
                    ORDER BY retry_at, account_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE app.account_withdrawals job
                SET lease_token = :token, lease_expires_at = :expires,
                    attempt_count = attempt_count + 1
                FROM next_job
                WHERE job.account_id = next_job.account_id
                RETURNING job.account_id
                """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("token", token)
                .param("expires", now.plus(LEASE_DURATION).atOffset(ZoneOffset.UTC))
                .query(UUID.class).optional().map(id -> new Claim(id, token));
    }

    public Optional<Stage> stage(Claim claim, Instant now) {
        return jdbc.sql("""
                SELECT stage FROM app.account_withdrawals
                WHERE account_id = :account AND lease_token = :token AND lease_expires_at > :now
                """)
                .param("account", claim.accountId()).param("token", claim.leaseToken())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(String.class).optional().map(Stage::valueOf);
    }

    public boolean advance(Claim claim, Stage expected, Stage next, Instant now) {
        if (!((expected == Stage.UNLINK_PENDING && next == Stage.CLEANUP_PENDING)
                || (expected == Stage.CLEANUP_PENDING && next == Stage.FILES_PENDING))) {
            throw new IllegalArgumentException("Invalid withdrawal stage transition");
        }
        return jdbc.sql("""
                UPDATE app.account_withdrawals
                SET stage = :next, error_code = NULL
                WHERE account_id = :account AND lease_token = :token
                  AND lease_expires_at > :now AND stage = :expected
                """)
                .param("next", next.name()).param("expected", expected.name())
                .param("account", claim.accountId()).param("token", claim.leaseToken())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    public boolean retry(Claim claim, String errorCode, Instant retryAt, Instant now) {
        return jdbc.sql("""
                UPDATE app.account_withdrawals
                SET lease_token = NULL, lease_expires_at = NULL, retry_at = :retry, error_code = :error
                WHERE account_id = :account AND lease_token = :token AND lease_expires_at > :now
                """)
                .param("retry", retryAt.atOffset(ZoneOffset.UTC)).param("error", errorCode)
                .param("account", claim.accountId()).param("token", claim.leaseToken())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    public Optional<String> subject(Claim claim, Instant now) {
        return jdbc.sql("""
                SELECT account.kakao_subject FROM app.accounts account
                JOIN app.account_withdrawals job ON job.account_id = account.id
                WHERE job.account_id = :account AND job.lease_token = :token
                  AND job.lease_expires_at > :now AND account.status = 'WITHDRAWING'
                """).param("account", claim.accountId()).param("token", claim.leaseToken())
                .param("now", now.atOffset(ZoneOffset.UTC)).query(String.class).optional();
    }

    public Optional<Long> attemptCount(Claim claim, Instant now) {
        return jdbc.sql("""
                SELECT attempt_count FROM app.account_withdrawals
                WHERE account_id = :account AND lease_token = :token AND lease_expires_at > :now
                """).param("account", claim.accountId()).param("token", claim.leaseToken())
                .param("now", now.atOffset(ZoneOffset.UTC)).query(Long.class).optional();
    }

    // Caller holds the account lock: re-consent must invalidate every in-flight cleanup lease.
    public void rearmUnlink(UUID accountId, Instant now) {
        int changed = jdbc.sql("""
                UPDATE app.account_withdrawals SET stage = 'UNLINK_PENDING', lease_token = NULL,
                    lease_expires_at = NULL, retry_at = :now, error_code = NULL
                WHERE account_id = :account
                """).param("account", accountId).param("now", now.atOffset(ZoneOffset.UTC)).update();
        if (changed != 1) throw new IllegalStateException("Withdrawal job is missing");
    }

    public enum Stage { UNLINK_PENDING, CLEANUP_PENDING, FILES_PENDING }
    public record Claim(UUID accountId, UUID leaseToken) { }
}
