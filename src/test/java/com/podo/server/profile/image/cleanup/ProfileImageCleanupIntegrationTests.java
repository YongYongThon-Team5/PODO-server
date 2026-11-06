package com.podo.server.profile.image.cleanup;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.profile.image.repository.ProfileImageRepository;
import com.podo.server.profile.image.storage.ProfileImageStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '3s'"
})
@Testcontainers
class ProfileImageCleanupIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final Instant AT_CUTOFF = Instant.parse("2026-11-05T10:00:00Z");
    private static final Instant BEFORE_CUTOFF = Instant.parse("2026-11-05T09:59:59Z");
    private static final Instant AFTER_CUTOFF = Instant.parse("2026-11-05T10:00:01Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ProfileImageCleanup cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient jdbcClient;
    @Autowired ProfileImageRepository images;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean ProfileImageStorage storage;
    @MockitoBean Clock clock;

    private UUID accountId;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE app.profile_image_deletions, app.child_profiles, app.profile_images, app.accounts CASCADE");
        accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.accounts (id, kakao_subject, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', now(), now())
                """, accountId, accountId.toString());
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void expiresUnlinkedImagesAfterTwentyFourHoursAndKeepsRecentOrReferencedImages() {
        image("expired.webp", AT_CUTOFF);
        UUID recent = image("recent.webp", AFTER_CUTOFF);
        UUID linked = image("linked.webp", null);
        UUID referencedDespiteStaleMarker = image("referenced.webp", BEFORE_CUTOFF);
        jdbc.update("""
                INSERT INTO app.child_profiles (id, account_id, nickname, color_code, profile_image_id, created_at, updated_at)
                VALUES (?, ?, 'Child', 'RED', ?, now(), now())
                """, UUID.randomUUID(), accountId, referencedDespiteStaleMarker);

        cleanup.run();

        assertThat(imageIds()).containsExactlyInAnyOrder(recent, linked, referencedDespiteStaleMarker);
        assertThat(pendingKeys()).isEmpty();
        verify(storage).delete("expired.webp");
        verifyNoMoreInteractions(storage);
    }

    @Test
    void skipsAnExpiredImageWhileItsBytesCanBeReadUnderASharedLock() {
        UUID expired = image("reading.webp", BEFORE_CUTOFF);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(images.findByIdAndAccountId(expired, accountId)).isPresent();

            cleanup.run();

            assertThat(imageIds()).containsExactly(expired);
            verifyNoMoreInteractions(storage);
        });

        cleanup.run();

        assertThat(imageIds()).isEmpty();
        verify(storage).delete("reading.webp");
    }

    @Test
    void retainsFailedDeletionsForRetryAndContinuesDeletingOtherImages() {
        image("retry.webp", BEFORE_CUTOFF);
        image("succeeds.webp", AT_CUTOFF);
        var separateTransaction = new TransactionTemplate(transactionManager);
        separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            separateTransaction.executeWithoutResult(status -> {
                assertThat(imageIds()).isEmpty();
                assertThat(pendingKeys()).containsExactlyInAnyOrder("retry.webp", "succeeds.webp");
            });
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }).doNothing().when(storage).delete("retry.webp");

        cleanup.run();

        assertThat(imageIds()).isEmpty();
        assertThat(pendingKeys()).containsExactly("retry.webp");

        when(clock.instant()).thenReturn(Instant.parse("2026-11-06T11:00:00Z"));
        cleanup.run();

        assertThat(pendingKeys()).isEmpty();
        verify(storage).delete("succeeds.webp");
    }

    @Test
    void preservesTheFileOfALiveImageWithTheSameLegacyStorageKey() {
        image("shared.webp", BEFORE_CUTOFF);
        UUID live = image("shared.webp", null);

        cleanup.run();

        assertThat(imageIds()).containsExactly(live);
        assertThat(pendingKeys()).isEmpty();
        verifyNoMoreInteractions(storage);
    }

    @Test
    void deletesAllExpiredFilesAcrossBatchBoundaries(@TempDir Path directory) throws IOException {
        var diskStorage = new ProfileImageStorage(directory.toString());
        var cleanupWithDisk = new ProfileImageCleanup(jdbcClient, diskStorage, clock, transactionManager);
        for (int index = 0; index < 101; index++) {
            String key = UUID.randomUUID().toString();
            diskStorage.write(key, new byte[] {1, 2, 3});
            image(key, BEFORE_CUTOFF);
        }
        String recentKey = UUID.randomUUID().toString();
        diskStorage.write(recentKey, new byte[] {4, 5, 6});
        UUID recent = image(recentKey, AFTER_CUTOFF);
        jdbc.update("""
                INSERT INTO app.profile_image_deletions (image_id, storage_key, retry_at)
                SELECT gen_random_uuid(), gen_random_uuid()::text, ? FROM generate_series(1, 100)
                """, Timestamp.from(BEFORE_CUTOFF));

        cleanupWithDisk.run();

        assertThat(imageIds()).containsExactly(recent);
        assertThat(pendingKeys()).isEmpty();
        try (var remainingFiles = Files.list(directory)) {
            assertThat(remainingFiles.toList()).containsExactly(directory.resolve(recentKey));
        }
    }

    private UUID image(String key, Instant orphanedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.profile_images (id, account_id, storage_key, mime_type, size_bytes, orphaned_at, created_at)
                VALUES (?, ?, ?, 'image/webp', 64, ?, now())
                """, id, accountId, key, orphanedAt == null ? null : Timestamp.from(orphanedAt));
        return id;
    }

    private List<UUID> imageIds() {
        return jdbc.queryForList("SELECT id FROM app.profile_images", UUID.class);
    }

    private List<String> pendingKeys() {
        return jdbc.queryForList("SELECT storage_key FROM app.profile_image_deletions", String.class);
    }
}
