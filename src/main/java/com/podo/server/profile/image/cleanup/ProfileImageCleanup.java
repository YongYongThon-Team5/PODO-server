package com.podo.server.profile.image.cleanup;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.profile.image.storage.ProfileImageStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Slf4j
public class ProfileImageCleanup {
    private static final int BATCH_SIZE = 100;
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration RETRY_DELAY = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final ProfileImageStorage storage;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ProfileImageCleanup(JdbcClient jdbc, ProfileImageStorage storage, Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    public void run() {
        OffsetDateTime now = clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
        int imagesEnqueued;
        int filesProcessed;
        do {
            imagesEnqueued = transaction.execute(status -> enqueueExpiredImages(now));
            filesProcessed = transaction.execute(status -> deleteQueuedFiles(now));
        } while (imagesEnqueued == BATCH_SIZE || filesProcessed == BATCH_SIZE);
    }

    private int enqueueExpiredImages(OffsetDateTime now) {
        return jdbc.sql("""
                WITH expired AS (
                    SELECT image.id
                    FROM app.profile_images image
                    WHERE image.orphaned_at <= :cutoff
                      AND NOT EXISTS (
                          SELECT 1 FROM app.child_profiles profile WHERE profile.profile_image_id = image.id
                      )
                    ORDER BY image.orphaned_at, image.id
                    LIMIT :batchSize
                    FOR UPDATE OF image SKIP LOCKED
                ), removed AS (
                    DELETE FROM app.profile_images image USING expired
                    WHERE image.id = expired.id
                    RETURNING image.id, image.storage_key
                )
                INSERT INTO app.profile_image_deletions (image_id, storage_key, retry_at)
                SELECT id, storage_key, :now FROM removed
                """)
                .param("cutoff", now.minus(RETENTION))
                .param("batchSize", BATCH_SIZE)
                .param("now", now)
                .update();
    }

    private int deleteQueuedFiles(OffsetDateTime now) {
        // The same key can occur in legacy metadata. Keep its file while any image still uses it.
        var deletions = jdbc.sql("""
                SELECT pending.image_id, pending.storage_key, EXISTS (
                    SELECT 1 FROM app.profile_images image WHERE image.storage_key = pending.storage_key
                ) AS still_used
                FROM app.profile_image_deletions pending
                WHERE pending.retry_at <= :now
                ORDER BY pending.retry_at, pending.image_id
                LIMIT :batchSize
                FOR UPDATE OF pending SKIP LOCKED
                """)
                .param("now", now)
                .param("batchSize", BATCH_SIZE)
                .query((rs, rowNum) -> new Deletion(
                        rs.getObject("image_id", UUID.class),
                        rs.getString("storage_key"),
                        rs.getBoolean("still_used")))
                .list();

        for (Deletion deletion : deletions) {
            try {
                if (!deletion.stillUsed()) {
                    storage.delete(deletion.storageKey());
                }
            } catch (BusinessException ex) {
                log.warn("Will retry file deletion for expired profile image {}", deletion.imageId());
                jdbc.sql("UPDATE app.profile_image_deletions SET retry_at = :retryAt WHERE image_id = :id")
                        .param("retryAt", now.plus(RETRY_DELAY))
                        .param("id", deletion.imageId())
                        .update();
                continue;
            }
            jdbc.sql("DELETE FROM app.profile_image_deletions WHERE image_id = :id")
                    .param("id", deletion.imageId())
                    .update();
        }
        return deletions.size();
    }

    private record Deletion(UUID imageId, String storageKey, boolean stillUsed) {}
}
