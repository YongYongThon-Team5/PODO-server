package com.podo.server.auth.withdrawal;

import com.podo.server.observation.repository.ObservationJobRepository;
import com.podo.server.observation.storage.ObservationStorage;
import com.podo.server.profile.image.storage.ProfileImageStorage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static com.podo.server.auth.withdrawal.WithdrawalRepository.Stage.*;

@Service
public class WithdrawalCleanup {
    private static final String OWNED_OBSERVATIONS = """
            SELECT o.id FROM app.observations o JOIN app.visits v ON v.id = o.visit_id
            WHERE v.account_id = :account
            """;
    private static final String OWNED_SETS = """
            SELECT s.id FROM app.quiz_sets s
            WHERE s.observation_id IN (%s)
            """.formatted(OWNED_OBSERVATIONS);
    private final JdbcClient jdbc;
    private final WithdrawalRepository withdrawals;
    private final ProfileImageStorage profiles;
    private final ObservationStorage observations;
    private final ObservationJobRepository observationJobs;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public WithdrawalCleanup(JdbcClient jdbc, WithdrawalRepository withdrawals, ProfileImageStorage profiles,
                             ObservationStorage observations, ObservationJobRepository observationJobs, Clock clock,
                             PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.withdrawals = withdrawals;
        this.profiles = profiles;
        this.observations = observations;
        this.observationJobs = observationJobs;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void prepare(UUID accountId, UUID leaseToken) {
        transaction.executeWithoutResult(status -> {
            var claim = new WithdrawalRepository.Claim(accountId, leaseToken);
            var stage = requireOwnership(claim);
            if (stage == FILES_PENDING) return;
            if (stage != CLEANUP_PENDING) throw changed();

            lock("SELECT id FROM app.visits WHERE account_id = :account ORDER BY id FOR UPDATE", accountId);
            lock(OWNED_SETS + " ORDER BY s.id FOR UPDATE", accountId);
            // Pin image metadata before the existing orphan worker can remove its ownership association.
            lock("SELECT id FROM app.profile_images WHERE account_id = :account ORDER BY id FOR UPDATE", accountId);
            lock("""
                    SELECT image_id FROM app.profile_image_deletions
                    WHERE account_id = :account OR image_id IN (
                        SELECT id FROM app.profile_images WHERE account_id = :account
                    ) ORDER BY image_id FOR UPDATE
                    """, accountId);
            for (var file : ownedFiles(accountId)) {
                int saved = jdbc.sql("""
                        INSERT INTO app.account_withdrawal_files(account_id, storage_kind, storage_key)
                        VALUES (:account, :kind, :key)
                        ON CONFLICT (storage_kind, storage_key) DO UPDATE SET done = false
                        WHERE account_withdrawal_files.account_id = EXCLUDED.account_id
                        """)
                        .param("account", accountId).param("kind", file.kind()).param("key", file.key()).update();
                // Another withdrawing account keeps this key until its own job releases it.
                if (saved != 1) throw new IllegalStateException("Withdrawal file is owned by another cleanup job");
            }

            // visits' status CHECK requires clearing its progress fields together with the set FK.
            update("""
                    UPDATE app.visits SET quiz_set_id = NULL, status = 'CANCELLED', reported_at = NULL,
                        completed_at = NULL, cancelled_at = COALESCE(cancelled_at, CURRENT_TIMESTAMP)
                    WHERE account_id = :account
                    """, accountId);
            update("DELETE FROM app.quiz_answers WHERE visit_id IN (SELECT id FROM app.visits WHERE account_id = :account)", accountId);
            update("DELETE FROM app.quiz_hint_views WHERE visit_id IN (SELECT id FROM app.visits WHERE account_id = :account)", accountId);
            update("DELETE FROM app.quiz_questions WHERE quiz_set_id IN (" + OWNED_SETS + ")", accountId);
            update("DELETE FROM app.quiz_sets WHERE id IN (" + OWNED_SETS + ")", accountId);
            update("DELETE FROM app.observation_images WHERE observation_id IN (" + OWNED_OBSERVATIONS + ")", accountId);
            update("DELETE FROM app.observations WHERE id IN (" + OWNED_OBSERVATIONS + ")", accountId);
            update("DELETE FROM app.visits WHERE account_id = :account", accountId);
            update("DELETE FROM app.child_profiles WHERE account_id = :account", accountId);
            update("""
                    DELETE FROM app.profile_image_deletions WHERE account_id = :account OR image_id IN (
                        SELECT id FROM app.profile_images WHERE account_id = :account
                    )
                    """, accountId);
            update("DELETE FROM app.profile_images WHERE account_id = :account", accountId);
            update("DELETE FROM app.oauth_login_requests WHERE account_id = :account", accountId);
            // Retired-session cleanup also locks tokens before sessions; do not reverse that order.
            update("""
                    DELETE FROM app.auth_tokens WHERE session_id IN (
                        SELECT id FROM app.auth_sessions WHERE account_id = :account
                    )
                    """, accountId);
            update("DELETE FROM app.auth_sessions WHERE account_id = :account", accountId);
            if (!withdrawals.advance(claim, CLEANUP_PENDING, FILES_PENDING, clock.instant())) throw changed();
        });
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean deleteFiles(UUID accountId, UUID leaseToken) {
        var claim = new WithdrawalRepository.Claim(accountId, leaseToken);
        List<File> files = transaction.execute(status -> {
            if (ownedStage(claim) != FILES_PENDING) return null;
            return jdbc.sql("""
                    SELECT storage_kind, storage_key FROM app.account_withdrawal_files
                    WHERE account_id = :account AND NOT done ORDER BY storage_kind, storage_key
                    """).param("account", accountId).query((rs, n) -> new File(rs.getString(1), rs.getString(2))).list();
        });
        if (files == null) return false;
        for (var file : files) {
            boolean owned = Boolean.TRUE.equals(transaction.execute(status -> ownedStage(claim) == FILES_PENDING));
            if (!owned) return false;
            validateKey(file.key());
            // No transaction/row lock spans storage I/O. Keys are generated immutable UUIDs, never reused.
            boolean referenced = "PROFILE".equals(file.kind())
                    ? jdbc.sql("SELECT EXISTS (SELECT 1 FROM app.profile_images WHERE storage_key = :key)")
                        .param("key", file.key()).query(Boolean.class).single()
                    : observationJobs.referencesFile(file.key());
            if (!referenced) {
                if ("PROFILE".equals(file.kind())) profiles.delete(file.key());
                else observations.delete(file.key());
            }
            boolean recorded = Boolean.TRUE.equals(transaction.execute(status -> {
                if (ownedStage(claim) != FILES_PENDING) return false;
                jdbc.sql("""
                        UPDATE app.account_withdrawal_files SET done = true
                        WHERE account_id = :account AND storage_kind = :kind AND storage_key = :key
                        """).param("account", accountId).param("kind", file.kind()).param("key", file.key()).update();
                return true;
            }));
            if (!recorded) return false;
        }
        return Boolean.TRUE.equals(transaction.execute(status -> ownedStage(claim) == FILES_PENDING && !hasPending(accountId)));
    }

    public void finish(UUID accountId, UUID leaseToken) {
        transaction.executeWithoutResult(status -> {
            var claim = new WithdrawalRepository.Claim(accountId, leaseToken);
            if (requireOwnership(claim) != FILES_PENDING || hasPending(accountId)) throw changed();
            boolean personalRows = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM app.visits WHERE account_id = :account)
                        OR EXISTS (SELECT 1 FROM app.child_profiles WHERE account_id = :account)
                        OR EXISTS (SELECT 1 FROM app.profile_images WHERE account_id = :account)
                        OR EXISTS (SELECT 1 FROM app.auth_sessions WHERE account_id = :account)
                        OR EXISTS (SELECT 1 FROM app.oauth_login_requests WHERE account_id = :account)
                    """).param("account", accountId).query(Boolean.class).single();
            if (personalRows) throw new IllegalStateException("Withdrawal personal cleanup is incomplete");
            boolean orphanFiles = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM app.profile_image_deletions
                        WHERE account_id = :account OR account_id IS NULL)
                    """).param("account", accountId).query(Boolean.class).single();
            // Pre-V6 queue rows without recoverable provenance may belong to this account.
            if (orphanFiles) throw new IllegalStateException("Withdrawal orphan file cleanup is incomplete");
            if (requireOwnership(claim) != FILES_PENDING) throw changed();
            update("DELETE FROM app.account_withdrawal_files WHERE account_id = :account", accountId);
            update("DELETE FROM app.account_withdrawals WHERE account_id = :account", accountId);
            jdbc.sql("DELETE FROM app.accounts WHERE id = :account AND status = 'WITHDRAWING'")
                    .param("account", accountId).update();
        });
    }

    private List<File> ownedFiles(UUID accountId) {
        return jdbc.sql("""
                SELECT 'PROFILE' AS kind, storage_key FROM app.profile_images WHERE account_id = :account
                UNION
                SELECT 'PROFILE', storage_key FROM app.profile_image_deletions
                WHERE account_id = :account OR image_id IN (
                    SELECT id FROM app.profile_images WHERE account_id = :account
                )
                UNION
                SELECT 'OBSERVATION', storage_key FROM app.observations WHERE id IN (%s)
                UNION
                SELECT 'OBSERVATION', storage_key FROM app.observation_images WHERE observation_id IN (%s)
                ORDER BY 1, 2
                """.formatted(OWNED_OBSERVATIONS, OWNED_OBSERVATIONS))
                .param("account", accountId).query((rs, n) -> new File(rs.getString(1), rs.getString(2))).list();
    }

    private WithdrawalRepository.Stage requireOwnership(WithdrawalRepository.Claim claim) {
        var stage = ownedStage(claim);
        if (stage == null) throw changed();
        return stage;
    }

    // Read the token and expiry AFTER obtaining both locks: a pre-lock stage read cannot fence cleanup.
    private WithdrawalRepository.Stage ownedStage(WithdrawalRepository.Claim claim) {
        var account = jdbc.sql("SELECT status FROM app.accounts WHERE id = :account FOR UPDATE")
                .param("account", claim.accountId()).query(String.class).optional();
        if (account.filter("WITHDRAWING"::equals).isEmpty()) return null;
        return jdbc.sql("""
                SELECT stage, lease_token, lease_expires_at FROM app.account_withdrawals
                WHERE account_id = :account FOR UPDATE
                """).param("account", claim.accountId()).query((rs, n) -> {
                    var expiry = rs.getTimestamp("lease_expires_at");
                    return new Job(WithdrawalRepository.Stage.valueOf(rs.getString("stage")),
                            rs.getObject("lease_token", UUID.class), expiry == null ? null : expiry.toInstant());
                }).optional()
                .filter(job -> claim.leaseToken().equals(job.token()) && job.expires() != null && job.expires().isAfter(clock.instant()))
                .map(Job::stage).orElse(null);
    }

    private boolean hasPending(UUID accountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app.account_withdrawal_files WHERE account_id = :account AND NOT done)")
                .param("account", accountId).query(Boolean.class).single();
    }
    private void lock(String sql, UUID accountId) { jdbc.sql(sql).param("account", accountId).query(UUID.class).list(); }
    private void update(String sql, UUID accountId) { jdbc.sql(sql).param("account", accountId).update(); }
    private void validateKey(String key) {
        try {
            if (key != null && UUID.fromString(key).toString().equals(key)) return;
        } catch (IllegalArgumentException ignored) { }
        throw new IllegalStateException("Invalid withdrawal storage key");
    }
    private IllegalStateException changed() { return new IllegalStateException("Withdrawal ownership or stage changed"); }
    private record File(String kind, String key) { }
    private record Job(WithdrawalRepository.Stage stage, UUID token, Instant expires) { }
}
