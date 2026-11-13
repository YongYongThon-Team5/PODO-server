package com.podo.server.auth.withdrawal;

import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.auth.service.OAuthLoginStateService;
import com.podo.server.auth.support.OpaqueTokens;
import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.profile.image.service.ProfileImageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import com.podo.server.observation.ai.ObservationAi.*;
import com.podo.server.observation.domain.ObservationData.Lease;
import com.podo.server.observation.repository.ObservationJobRepository;
import com.podo.server.observation.service.ObservationGenerationTransactions;
import com.podo.server.observation.storage.ObservationStorage;
import com.podo.server.profile.image.cleanup.ProfileImageCleanup;
import com.podo.server.profile.image.storage.ProfileImageStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.podo.server.auth.withdrawal.WithdrawalRepository.Stage.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"podo.observation.worker-enabled=false", "podo.auth.withdrawal.worker-enabled=false"})
@Testcontainers
class WithdrawalCleanupIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final Path PROFILE = temporaryDirectory();
    private static final Path OBSERVATION = temporaryDirectory();
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17.11");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("podo.profile.image-directory", PROFILE::toString);
        registry.add("podo.observation.storage-directory", OBSERVATION::toString);
    }

    @TestConfiguration
    static class Configuration {
        @Bean @Primary MutableClock cleanupClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private volatile Instant now = NOW;
        void set(Instant instant) { now = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WithdrawalCleanup cleanup;
    @Autowired WithdrawalService withdrawal;
    @Autowired WithdrawalWorker worker;
    @MockitoBean KakaoUnlinkClient unlink;
    @Autowired WithdrawalRepository withdrawals;
    @Autowired AuthSessionService sessions;
    @Autowired ProfileImageService images;
    @Autowired OAuthLoginStateService states;
    @Autowired MutableClock clock;
    @Autowired ObservationGenerationTransactions generation;
    @Autowired ObservationJobRepository observations;
    @MockitoSpyBean ProfileImageStorage profiles;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObservationStorage storage;
    @Autowired ProfileImageCleanup orphanCleanup;
    private UUID account;
    private UUID other;
    private UUID place;
    private UUID source;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE app.accounts, app.places, app.profile_image_deletions CASCADE");
        clock.set(NOW);
        account = sessions.issueForKakaoSubject("cleanup-owned").accountId();
        other = sessions.issueForKakaoSubject("cleanup-other").accountId();
        place = UUID.randomUUID();
        source = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.places(id, name, address, latitude, longitude, main_activities, environment, status, updated_at)
                VALUES (?, '시험 장소', '용인', 37.2, 127.2, ARRAY['관찰'], 'INDOOR', 'PUBLISHED', now())
                """, place);
        jdbc.update("""
                INSERT INTO app.place_sources(id, place_id, source_type, fact_text, checked_at, status)
                VALUES (?, ?, 'OFFICIAL', '이 전시관에서는 용인의 역사와 문화를 소개합니다.', now(), 'ACTIVE')
                """, source, place);
    }

    @Test
    void uploadWaitsForAccountLockAndRejectsCommittedWithdrawal() throws Exception {
        byte[] photo = png();
        assertLateWriteWaitsForWithdrawal(() -> images.upload(account, photo, "image/png"), ErrorCode.INVALID_TOKEN, null);
        assertThat(count("profile_images")).isZero();
    }

    @Test
    void oauthLinkWaitsForAccountBeforeRequestAndRejectsCommittedWithdrawal() throws Exception {
        var challenge = consumedChallenge();
        assertLateWriteWaitsForWithdrawal(() -> states.linkAccount(challenge.state(), account), ErrorCode.INVALID_REQUEST, challenge.state());
        assertThat(jdbc.queryForObject("SELECT account_id FROM app.oauth_login_requests", UUID.class)).isNull();
    }

    @Test
    void authenticatedLateUploadCannotRecreateFilesAfterPrepareAndFinishImmediately() throws Exception {
        var login = sessions.issueForKakaoSubject("cleanup-owned");
        var tokens = sessions.rotateRefresh(login.refreshToken());
        var authenticated = sessions.authenticateAccess(tokens.accessToken());
        byte[] photo = png();
        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        assertThat(withdrawals.stage(claim, NOW)).contains(FILES_PENDING);
        List<Path> filesBefore;
        try (var files = Files.list(PROFILE)) { filesBefore = files.toList(); }

        assertThatThrownBy(() -> images.upload(authenticated.accountId(), photo, "image/png"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));
        assertThat(count("profile_images")).isZero();
        try (var files = Files.list(PROFILE)) { assertThat(files.toList()).containsExactlyInAnyOrderElementsOf(filesBefore); }
        assertThat(cleanup.deleteFiles(account, claim.leaseToken())).isTrue();
        cleanup.finish(account, claim.leaseToken());
        assertThat(count("account_withdrawals")).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM app.accounts", UUID.class)).containsExactly(other);
        assertThat(clock.instant()).isEqualTo(NOW);
    }

    @Test
    void issuedSessionLateOauthLinkCannotRecreateOwnershipAfterPrepareAndFinishImmediately() {
        var challenge = consumedChallenge();
        var login = sessions.issueForKakaoCallbackSubject("cleanup-owned");
        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        assertThat(withdrawals.stage(claim, NOW)).contains(FILES_PENDING);

        assertThatThrownBy(() -> states.linkAccount(challenge.state(), login.accountId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(jdbc.queryForObject("SELECT account_id FROM app.oauth_login_requests", UUID.class)).isNull();
        assertThat(cleanup.deleteFiles(account, claim.leaseToken())).isTrue();
        cleanup.finish(account, claim.leaseToken());
        assertThat(count("account_withdrawals")).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM app.accounts", UUID.class)).containsExactly(other);
        assertThat(clock.instant()).isEqualTo(NOW);
    }

    private OAuthLoginStateService.LoginChallenge consumedChallenge() {
        var challenge = states.issue();
        states.consume(challenge.state(), challenge.browserNonce());
        return challenge;
    }

    private void assertLateWriteWaitsForWithdrawal(Runnable write, ErrorCode expected, String oauthState) throws Exception {
        when(unlink.ready()).thenReturn(true);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var acceptance = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM app.accounts WHERE id = ? FOR UPDATE", UUID.class, account);
                locked.countDown();
                await(release);
                withdrawal.request(account);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var writer = executor.submit(() -> {
                try {
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        write.run();
                    });
                    return null;
                } catch (BusinessException ex) { return ex.getErrorCode(); }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting;
                do {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, writerPid.get()));
                    if (!waiting) Thread.sleep(20);
                } while (!waiting && System.nanoTime() < deadline);
                assertThat(waiting).as("late write waits for the account transaction").isTrue();
                if (oauthState != null) {
                    // The waiting linker must not hold the request before obtaining the account.
                    String hash = OpaqueTokens.hash(oauthState);
                    assertThat(jdbc.queryForObject("SELECT state_hash FROM app.oauth_login_requests WHERE state_hash = ? FOR UPDATE NOWAIT",
                            String.class, hash)).isEqualTo(hash);
                }
            } finally { release.countDown(); }
            acceptance.get(10, TimeUnit.SECONDS);
            assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
        } finally { release.countDown(); }
    }

    private static byte[] png() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void acceptedWorkerDeletesAllPrivateTypesPreservesSharedDataAndRejoinsWithNewUuid() throws IOException {
        when(unlink.ready()).thenReturn(true);
        when(unlink.unlink("cleanup-owned")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return UnlinkResult.CONFIRMED;
        });
        String photo = key();
        child(account, image(account, photo), "BLUE");
        profiles.write(photo, new byte[]{1});
        UUID ownedVisit = visit(account);
        UUID otherVisit = visit(other);
        UUID shared = quiz(null, 1);
        UUID sharedQuestion = question(shared, null);
        answer(ownedVisit, sharedQuestion);
        answer(otherVisit, sharedQuestion);
        String observationKey = key();
        UUID observation = observation(ownedVisit, observationKey);
        UUID observationImage = observationImage(observation, observationKey, 1);
        UUID personal = quiz(observation, 1);
        answer(ownedVisit, question(personal, observationImage));
        attachSet(ownedVisit, personal);
        storage.write(observationKey, new byte[]{2});
        jdbc.update("INSERT INTO app.oauth_login_requests VALUES ('worker-state', 'nonce', ?, now(), NULL)", account);
        withdrawal.request(account);
        new TransactionTemplate(manager).executeWithoutResult(status -> worker.runOnce());
        assertThat(jdbc.queryForList("SELECT id FROM app.accounts", UUID.class)).containsExactly(other);
        for (String table : List.of("account_withdrawals", "account_withdrawal_files", "child_profiles", "profile_images",
                "observations", "observation_images", "oauth_login_requests")) assertThat(count(table)).as(table).isZero();
        assertThat(count("visits")).isEqualTo(1);
        assertThat(count("quiz_answers")).isEqualTo(1);
        assertThat(count("quiz_hint_views")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM app.quiz_sets", UUID.class)).containsExactly(shared);
        assertThat(count("quiz_questions")).isEqualTo(1);
        assertThat(count("places")).isEqualTo(1);
        assertThat(count("place_sources")).isEqualTo(1);
        assertThat(Files.exists(PROFILE.resolve(photo))).isFalse();
        assertThat(Files.exists(OBSERVATION.resolve(observationKey))).isFalse();
        assertThat(sessions.issueForKakaoSubject("cleanup-owned").accountId()).isNotEqualTo(account);
    }

    @Test
    void cleanupDeletesOnlyOwnedDataAndPersistsFiles() throws IOException {
        String ownedPhoto = key();
        String sharedPhoto = key();
        UUID image = image(account, ownedPhoto);
        child(account, image, "BLUE");
        child(account, image, "RED"); // Two children share one metadata row.
        image(account, ownedPhoto); // Legacy duplicate storage key.
        image(account, sharedPhoto);
        image(other, sharedPhoto); // A distinct account still owns this physical file.
        profiles.write(ownedPhoto, new byte[]{1});
        profiles.write(sharedPhoto, new byte[]{2});
        UUID visit = visit(account);
        UUID oldVisit = visit(account);
        UUID otherVisit = visit(other);
        UUID common = quiz(null, 1);
        UUID commonQuestion = question(common, null);
        answer(visit, commonQuestion);
        answer(otherVisit, commonQuestion);
        String legacyKey = key();
        UUID legacy = observation(oldVisit, legacyKey);
        String first = key();
        UUID observation = observation(visit, first);
        UUID firstImage = observationImage(observation, first, 1);
        String second = key();
        observationImage(observation, second, 2);
        UUID oldSet = quiz(observation, 1);
        UUID currentSet = quiz(observation, 2);
        UUID legacySet = quiz(legacy, 1);
        question(oldSet, firstImage);
        UUID currentQuestion = question(currentSet, firstImage);
        question(legacySet, null);
        answer(visit, currentQuestion);
        attachSet(visit, currentSet);
        attachSet(oldVisit, legacySet);
        String otherKey = key();
        UUID otherObservation = observation(otherVisit, otherKey);
        UUID otherSet = quiz(otherObservation, 1);
        question(otherSet, null);
        storage.write(first, new byte[]{3});
        storage.write(second, new byte[]{4});
        storage.write(legacyKey, new byte[]{5});
        storage.write(otherKey, new byte[]{6});
        jdbc.update("INSERT INTO app.oauth_login_requests VALUES ('owned-state', 'nonce', ?, now(), NULL)", account);
        jdbc.update("INSERT INTO app.profile_image_deletions(image_id, storage_key, retry_at) VALUES (?, ?, now())", image, ownedPhoto);
        UUID unownedQueueId = UUID.randomUUID();
        String unownedQueueKey = key();
        jdbc.update("INSERT INTO app.profile_image_deletions(image_id, storage_key, retry_at) VALUES (?, ?, now())", unownedQueueId, unownedQueueKey);
        profiles.write(unownedQueueKey, new byte[]{9});

        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        cleanup.prepare(account, claim.leaseToken()); // Durable stage makes retry idempotent.

        assertThat(withdrawals.stage(claim, NOW)).contains(FILES_PENDING);
        assertThat(count("account_withdrawal_files")).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT storage_key FROM app.account_withdrawal_files", String.class))
                .containsExactlyInAnyOrder(ownedPhoto, sharedPhoto, legacyKey, first, second);
        assertThat(count("visits")).isEqualTo(1);
        assertThat(count("observations")).isEqualTo(1);
        assertThat(count("observation_images")).isZero();
        assertThat(count("quiz_sets")).isEqualTo(2);
        assertThat(count("quiz_questions")).isEqualTo(2);
        assertThat(count("quiz_answers")).isEqualTo(1);
        assertThat(count("quiz_hint_views")).isEqualTo(1);
        assertThat(count("child_profiles")).isZero();
        assertThat(count("profile_images")).isEqualTo(1);
        assertThat(count("profile_image_deletions")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT image_id FROM app.profile_image_deletions", UUID.class)).isEqualTo(unownedQueueId);
        assertThat(count("oauth_login_requests")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_sessions WHERE account_id = ?", Integer.class, account)).isZero();
        assertThat(count("accounts")).isEqualTo(2);
        assertThatThrownBy(() -> sessions.issueForKakaoSubject("cleanup-owned"));

        assertThat(cleanup.deleteFiles(account, claim.leaseToken())).isTrue();
        assertThat(Files.exists(PROFILE.resolve(ownedPhoto))).isFalse();
        assertThat(Files.readAllBytes(PROFILE.resolve(sharedPhoto))).containsExactly((byte) 2);
        assertThat(Files.exists(OBSERVATION.resolve(first))).isFalse();
        assertThat(Files.exists(OBSERVATION.resolve(second))).isFalse();
        assertThat(Files.exists(OBSERVATION.resolve(legacyKey))).isFalse();
        assertThat(storage.read(otherKey)).containsExactly((byte) 6);
        assertThatThrownBy(() -> cleanup.finish(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(PROFILE.resolve(unownedQueueKey))).isTrue();
        orphanCleanup.run(); // Legacy unknown-owner files drain only through the generic worker.
        cleanup.finish(account, claim.leaseToken());
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("account_withdrawals")).isZero();
        assertThat(count("account_withdrawal_files")).isZero();
        assertThat(Files.exists(PROFILE.resolve(unownedQueueKey))).isFalse();
        assertThat(count("places")).isEqualTo(1);
        assertThat(count("place_sources")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM app.quiz_sets", UUID.class)).containsExactlyInAnyOrder(common, otherSet);
        assertThat(sessions.issueForKakaoSubject("cleanup-owned").accountId()).isNotEqualTo(account);
    }

    @Test
    void failedFileDeleteBlocksFinish() throws IOException {
        String key = key();
        image(account, key);
        Files.createDirectory(PROFILE.resolve(key));
        Files.write(PROFILE.resolve(key).resolve("busy"), new byte[]{1});
        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        assertThatThrownBy(() -> cleanup.deleteFiles(account, claim.leaseToken())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT done FROM app.account_withdrawal_files", Boolean.class)).isFalse();
        assertThatThrownBy(() -> cleanup.finish(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(count("accounts")).isEqualTo(2);
        Files.delete(PROFILE.resolve(key).resolve("busy"));
        Files.delete(PROFILE.resolve(key)); // A file already absent is a successful retry.
        assertThat(cleanup.deleteFiles(account, claim.leaseToken())).isTrue();
        cleanup.finish(account, claim.leaseToken());
        assertThat(count("account_withdrawals")).isZero();
    }

    @Test
    void invalidKeyRemainsPending() {
        image(account, "../unsafe");
        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        assertThatThrownBy(() -> cleanup.deleteFiles(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT done FROM app.account_withdrawal_files", Boolean.class)).isFalse();
        assertThatThrownBy(() -> cleanup.finish(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void staleLeaseAndWrongStageCannotAuthorizeCleanup() {
        image(account, key());
        withdrawals.enqueue(account, NOW);
        var claim = withdrawals.claim(NOW).orElseThrow();
        assertThatThrownBy(() -> cleanup.prepare(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(count("profile_images")).isEqualTo(1);
        jdbc.update("UPDATE app.accounts SET status = 'WITHDRAWING' WHERE id = ?", account);
        assertThatThrownBy(() -> cleanup.prepare(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
        withdrawals.advance(claim, UNLINK_PENDING, CLEANUP_PENDING, NOW);
        clock.set(NOW.plusSeconds(300));
        assertThatThrownBy(() -> cleanup.prepare(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(count("profile_images")).isEqualTo(1);
        var current = withdrawals.claim(clock.instant()).orElseThrow();
        cleanup.prepare(account, current.leaseToken());
        assertThat(cleanup.deleteFiles(account, claim.leaseToken())).isFalse();
        assertThatThrownBy(() -> cleanup.finish(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(count("accounts")).isEqualTo(2);
        assertThat(cleanup.deleteFiles(account, current.leaseToken())).isTrue();
        cleanup.finish(account, current.leaseToken());
    }

    @Test
    void lateAiCompletionAndFailureCannotRecreateDeletedRows() {
        UUID visit = visit(account);
        jdbc.update("UPDATE app.visits SET status = 'PLANNED', cancelled_at = NULL WHERE id = ?", visit);
        UUID observation = observation(visit, key());
        observationImage(observation, key(), 1);
        jdbc.update("UPDATE app.observations SET request_key = 'request-key', request_hash = repeat('a', 64) WHERE id = ?", observation);
        Lease lease = generation.claim().orElseThrow();
        var snapshot = generation.snapshot(lease);
        Input input = new Input(snapshot.placeName(), List.of(new Photo(1, "image/png", new byte[]{1})), snapshot.sources());
        Output output = new Output("READY", "사진과 자료를 관찰해요.", java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(i -> new Question("시험 명제 " + i, "O", "자료와 일치해요.", source,
                        snapshot.sources().getFirst().factText(), 1, "사진에서 관찰한 시험 근거", "첫 단계 힌트", "둘째 단계 힌트", "안내판을 살펴봐요.")).toList());
        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        assertThatThrownBy(() -> generation.complete(lease, snapshot, input, output)).isInstanceOf(GenerationException.class);
        generation.failed(lease, FailureCode.UPSTREAM_TIMEOUT);
        assertThat(count("observations")).isZero();
        assertThat(count("quiz_sets")).isZero();
        assertThat(count("quiz_questions")).isZero();
    }

    @Test
    void fileIoSuspendsAnExistingCallerTransaction() {
        String key = key();
        image(account, key);
        profiles.write(key, new byte[]{1});
        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(profiles).delete(key);
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertThat(cleanup.deleteFiles(account, claim.leaseToken())).isTrue());
        assertThat(Files.exists(PROFILE.resolve(key))).isFalse();
    }

    @Test
    void expiredDuringFileDeleteCannotMarkDoneOrFinish() throws Exception {
        String key = key();
        image(account, key);
        profiles.write(key, new byte[]{1});
        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        var deleting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            deleting.countDown();
            await(release);
            return invocation.callRealMethod();
        }).when(profiles).delete(key);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldWorker = executor.submit(() -> cleanup.deleteFiles(account, claim.leaseToken()));
            assertThat(deleting.await(10, TimeUnit.SECONDS)).isTrue();
            clock.set(NOW.plusSeconds(300));
            // This succeeds while storage is blocked: no account/job lock spans file I/O.
            var current = withdrawals.claim(clock.instant()).orElseThrow();
            try {
                assertThatThrownBy(() -> cleanup.finish(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
                assertThat(count("accounts")).isEqualTo(2);
            } finally {
                release.countDown();
            }
            assertThat(oldWorker.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(jdbc.queryForObject("SELECT done FROM app.account_withdrawal_files", Boolean.class)).isFalse();
            assertThat(cleanup.deleteFiles(account, current.leaseToken())).isTrue();
            cleanup.finish(account, current.leaseToken());
        } finally {
            release.countDown();
        }
    }

    @Test
    void prepareChecksOwnershipAfterWaitingForAccountLock() throws Exception {
        image(account, key());
        var claim = begin();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var blocker = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM app.accounts WHERE id = ? FOR UPDATE", UUID.class, account);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var oldWorker = executor.submit(() -> {
                try { cleanup.prepare(account, claim.leaseToken()); return false; }
                catch (IllegalStateException ex) { return true; }
            });
            try {
                assertThrows(TimeoutException.class, () -> oldWorker.get(500, TimeUnit.MILLISECONDS));
                clock.set(NOW.plusSeconds(300));
                withdrawals.claim(clock.instant()).orElseThrow();
            } finally {
                release.countDown();
            }
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(oldWorker.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(count("profile_images")).isEqualTo(1);
            assertThat(count("account_withdrawal_files")).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void conflictingJobFileOwnershipRollsBackAndRetriesAfterOwnerFinishes() {
        String key = key();
        image(account, key);
        image(other, key);
        profiles.write(key, new byte[]{1});
        var first = begin();
        cleanup.prepare(account, first.leaseToken());
        jdbc.update("UPDATE app.accounts SET status = 'WITHDRAWING' WHERE id = ?", other);
        withdrawals.enqueue(other, NOW);
        var second = withdrawals.claim(NOW).orElseThrow();
        withdrawals.advance(second, UNLINK_PENDING, CLEANUP_PENDING, NOW);
        assertThatThrownBy(() -> cleanup.prepare(other, second.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(withdrawals.stage(second, NOW)).contains(CLEANUP_PENDING);
        assertThat(count("profile_images")).isEqualTo(1);
        assertThat(cleanup.deleteFiles(account, first.leaseToken())).isTrue();
        assertThat(Files.exists(PROFILE.resolve(key))).isTrue();
        cleanup.finish(account, first.leaseToken());
        cleanup.prepare(other, second.leaseToken());
        assertThat(cleanup.deleteFiles(other, second.leaseToken())).isTrue();
        cleanup.finish(other, second.leaseToken());
        assertThat(Files.exists(PROFILE.resolve(key))).isFalse();
    }

    @Test
    void foreignReferenceFailureRollsBackFilesAndEveryDatabaseDeletion() {
        UUID visit = visit(account);
        UUID observation = observation(visit, key());
        UUID set = quiz(observation, 1);
        UUID question = question(set, null);
        attachSet(visit, set);
        answer(visit, question);
        UUID otherVisit = visit(other);
        attachSet(otherVisit, set); // Invalid legacy ownership must never erase this other visit.
        var claim = begin();

        assertThatThrownBy(() -> cleanup.prepare(account, claim.leaseToken())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(withdrawals.stage(claim, NOW)).contains(CLEANUP_PENDING);
        assertThat(count("account_withdrawal_files")).isZero();
        assertThat(count("observations")).isEqualTo(1);
        assertThat(count("quiz_sets")).isEqualTo(1);
        assertThat(count("quiz_questions")).isEqualTo(1);
        assertThat(count("quiz_answers")).isEqualTo(1);
        assertThat(count("quiz_hint_views")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT quiz_set_id FROM app.visits", UUID.class)).containsExactly(set, set);
    }

    @Test
    void retiredSessionCleanupAndPrepareUseTokenThenSessionOrder() throws Exception {
        var login = sessions.issueForKakaoSubject("cleanup-owned");
        jdbc.update("UPDATE app.auth_sessions SET expires_at = ? WHERE id = ?", NOW.minusSeconds(172800).atOffset(ZoneOffset.UTC), login.sessionId());
        var claim = begin();
        var tokensDeleted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var prepareStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var retirement = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update("DELETE FROM app.auth_tokens WHERE session_id = ?", login.sessionId());
                tokensDeleted.countDown();
                await(release);
                jdbc.update("DELETE FROM app.auth_sessions WHERE id = ?", login.sessionId());
            }));
            assertThat(tokensDeleted.await(10, TimeUnit.SECONDS)).isTrue();
            var preparation = executor.submit(() -> {
                prepareStarted.countDown();
                cleanup.prepare(account, claim.leaseToken());
            });
            try {
                assertThat(prepareStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThrows(TimeoutException.class, () -> preparation.get(500, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            retirement.get(10, TimeUnit.SECONDS);
            preparation.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.auth_sessions WHERE account_id = ?", Integer.class, account)).isZero();
            assertThat(withdrawals.stage(claim, NOW)).contains(FILES_PENDING);
        } finally {
            release.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    @Test
    void withdrawalCapturesFailedOrphanDeletionAfterMetadataIsGone() throws IOException {
        String key = key();
        UUID image = image(account, key);
        jdbc.update("UPDATE app.profile_images SET orphaned_at = ? WHERE id = ?", NOW.minusSeconds(86401).atOffset(ZoneOffset.UTC), image);
        Files.createDirectory(PROFILE.resolve(key));
        Files.write(PROFILE.resolve(key).resolve("busy"), new byte[]{1});

        orphanCleanup.run();
        assertThat(count("profile_images")).isZero();
        assertThat(count("profile_image_deletions")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT account_id FROM app.profile_image_deletions WHERE image_id = ?", UUID.class, image)).isEqualTo(account);
        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        assertThat(count("account_withdrawal_files")).isEqualTo(1);
        assertThat(count("profile_image_deletions")).isZero();
        assertThatThrownBy(() -> cleanup.deleteFiles(account, claim.leaseToken())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cleanup.finish(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(count("accounts")).isEqualTo(2);
        assertThatThrownBy(() -> sessions.issueForKakaoSubject("cleanup-owned"));

        Files.delete(PROFILE.resolve(key).resolve("busy"));
        Files.delete(PROFILE.resolve(key));
        profiles.write(key, new byte[]{7});
        assertThat(cleanup.deleteFiles(account, claim.leaseToken())).isTrue();
        assertThat(Files.exists(PROFILE.resolve(key))).isFalse();
        cleanup.finish(account, claim.leaseToken());
        assertThat(sessions.issueForKakaoSubject("cleanup-owned").accountId()).isNotEqualTo(account);
    }

    @Test
    void legacyUnknownOwnerQueueBlocksFinishUntilGenericCleanupDrainsIt() {
        String key = key();
        profiles.write(key, new byte[]{1});
        jdbc.update("INSERT INTO app.profile_image_deletions(image_id, storage_key, retry_at) VALUES (?, ?, ?)",
                UUID.randomUUID(), key, NOW.atOffset(ZoneOffset.UTC));
        var claim = begin();
        cleanup.prepare(account, claim.leaseToken());
        assertThat(count("account_withdrawal_files")).isZero();
        assertThat(cleanup.deleteFiles(account, claim.leaseToken())).isTrue();
        assertThatThrownBy(() -> cleanup.finish(account, claim.leaseToken())).isInstanceOf(IllegalStateException.class);
        assertThat(count("accounts")).isEqualTo(2);
        assertThat(Files.exists(PROFILE.resolve(key))).isTrue();
        assertThat(count("profile_image_deletions")).isEqualTo(1);
        orphanCleanup.run();
        assertThat(Files.exists(PROFILE.resolve(key))).isFalse();
        cleanup.finish(account, claim.leaseToken());
    }

    private WithdrawalRepository.Claim begin() {
        jdbc.update("UPDATE app.accounts SET status = 'WITHDRAWING' WHERE id = ?", account);
        withdrawals.enqueue(account, clock.instant());
        var claim = withdrawals.claim(clock.instant()).orElseThrow();
        assertThat(withdrawals.advance(claim, UNLINK_PENDING, CLEANUP_PENDING, clock.instant())).isTrue();
        return claim;
    }

    private UUID image(UUID owner, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.profile_images VALUES (?, ?, ?, 'image/webp', 1, NULL, now())", id, owner, key);
        return id;
    }
    private void child(UUID owner, UUID image, String color) {
        jdbc.update("""
                INSERT INTO app.child_profiles(id, account_id, nickname, color_code, profile_image_id, created_at, updated_at)
                VALUES (?, ?, '아이', ?, ?, now(), now())
                """, UUID.randomUUID(), owner, color, image);
    }
    private UUID visit(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.visits(id, account_id, place_id, planned_date, source, status, create_request_key, create_request_hash,
                    cancelled_at, created_at, updated_at)
                VALUES (?, ?, ?, current_date, 'MAP', 'CANCELLED', ?, 'hash', now(), now(), now())
                """, id, owner, place, key());
        return id;
    }
    private UUID observation(UUID visit, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.observations(id, visit_id, storage_key, mime_type, size_bytes, created_at, updated_at)
                VALUES (?, ?, ?, 'image/png', 1, now(), now())
                """, id, visit, key);
        return id;
    }
    private UUID observationImage(UUID observation, String key, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.observation_images VALUES (?, ?, ?, ?, 'image/png', 1, now())", id, observation, position, key);
        return id;
    }
    private UUID quiz(UUID observation, int version) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.quiz_sets(id, place_id, version, status, observation_id) VALUES (?, ?, ?, 'DRAFT', ?)",
                id, place, version, observation);
        return id;
    }
    private UUID question(UUID set, UUID image) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.quiz_questions(id, quiz_set_id, position, question_text, correct_answer, explanation, source_id, observation_image_id)
                VALUES (?, ?, 1, '질문', 'O', '설명', ?, ?)
                """, id, set, source, image);
        return id;
    }
    private void answer(UUID visit, UUID question) {
        jdbc.update("INSERT INTO app.quiz_answers VALUES (?, ?, 'O', true, now())", visit, question);
        jdbc.update("INSERT INTO app.quiz_hint_views VALUES (?, ?, 1, now())", visit, question);
    }
    private void attachSet(UUID visit, UUID set) {
        jdbc.update("""
                UPDATE app.visits SET status = 'COMPLETED', quiz_set_id = ?, reported_at = now(), completed_at = now(), cancelled_at = NULL
                WHERE id = ?
                """, set, visit);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app." + table, Integer.class); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("podo-withdrawal-cleanup-"); }
        catch (IOException ex) { throw new IllegalStateException(ex); }
    }
}
