package com.podo.server.observation;

import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.observation.ai.ObservationAi.*;
import com.podo.server.observation.domain.ObservationData.*;
import com.podo.server.observation.service.ObservationGenerationTransactions;
import com.podo.server.observation.service.ObservationService;
import com.podo.server.observation.storage.ObservationStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "podo.observation.enabled=true",
        "podo.observation.api-key=test-placeholder",
        "podo.observation.auto-publish=false",
        "podo.observation.worker-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class ObservationIntegrationTests {

    @Container
    static final PostgreSQLContainer DB =
            new PostgreSQLContainer("postgres:17.11");

    static final Path STORAGE = temporaryDirectory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                DB::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                DB::getUsername
        );

        registry.add(
                "spring.datasource.password",
                DB::getPassword
        );

        registry.add(
                "podo.observation.storage-directory",
                STORAGE::toString
        );
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    AuthSessionService sessions;

    @Autowired
    ObservationService service;

    @Autowired
    ObservationGenerationTransactions transactions;

    @Autowired
    ObservationStorage storage;

    private UUID accountId;
    private String token;
    private UUID visitId;

    private final String fact =
            "이 전시관에서는 용인의 역사와 문화를 소개합니다.";

    @BeforeEach
    void reset() {
        jdbc.execute(
                "TRUNCATE app.accounts, app.places CASCADE"
        );

        var login = sessions.issueForKakaoSubject(
                "OBS_" + UUID.randomUUID()
        );

        var tokens = sessions.rotateRefresh(
                login.refreshToken()
        );

        accountId = tokens.accountId();
        token = tokens.accessToken();

        UUID place = UUID.randomUUID();
        visitId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO app.places (
                    id,
                    name,
                    address,
                    latitude,
                    longitude,
                    main_activities,
                    environment,
                    status,
                    updated_at
                )
                VALUES (
                    ?,
                    '시험 장소',
                    '용인',
                    37.2,
                    127.2,
                    ARRAY['관찰'],
                    'INDOOR',
                    'PUBLISHED',
                    now()
                )
                """, place);

        jdbc.update("""
                INSERT INTO app.place_sources (
                    id,
                    place_id,
                    source_type,
                    fact_text,
                    checked_at,
                    status
                )
                VALUES (?, ?, 'OFFICIAL', ?, now(), 'ACTIVE')
                """,
                UUID.randomUUID(),
                place,
                fact
        );

        jdbc.update("""
                INSERT INTO app.visits (
                    id,
                    account_id,
                    place_id,
                    planned_date,
                    source,
                    status,
                    create_request_key,
                    create_request_hash,
                    created_at,
                    updated_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    CURRENT_DATE,
                    'MAP',
                    'PLANNED',
                    ?,
                    'fixture',
                    now(),
                    now()
                )
                """,
                visitId,
                accountId,
                place,
                UUID.randomUUID().toString()
        );
    }

    @Test
    void threePhotosFormOneIdempotentObservationAndChangedPayloadIsRejected()
            throws Exception {
        String key = UUID.randomUUID().toString();

        State first = upload(key, 3);

        assertThat(first.images()).hasSize(3);

        assertThat(upload(key, 3).id())
                .isEqualTo(first.id());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.observations",
                Integer.class
        )).isEqualTo(1);

        mvc.perform(
                        multipart(path())
                                .file(photo())
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .header("Idempotency-Key", key)
                )
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("IDEMPOTENCY_KEY_REUSED")
                );
    }

    @Test
    void anotherAccountCannotReadTheBatchOrItsPhotos()
            throws Exception {
        State state = upload(
                UUID.randomUUID().toString(),
                1
        );

        var login = sessions.issueForKakaoSubject(
                "OTHER_" + UUID.randomUUID()
        );

        String other = sessions.rotateRefresh(
                login.refreshToken()
        ).accessToken();

        mvc.perform(
                get(path() + "/" + state.id())
                        .header(
                                "Authorization",
                                "Bearer " + other
                        )
        ).andExpect(status().isNotFound());

        mvc.perform(
                get(state.images().getFirst().contentUrl())
                        .header(
                                "Authorization",
                                "Bearer " + other
                        )
        ).andExpect(status().isNotFound());
    }

    @Test
    void generationIsStoredAsDraftThenExistingQuizApisWorkAfterReview()
            throws Exception {
        State state = upload(
                UUID.randomUUID().toString(),
                3
        );

        Lease lease = transactions.claim().orElseThrow();

        var snapshot = transactions.snapshot(lease);
        Input input = input(snapshot);

        transactions.complete(
                lease,
                snapshot,
                input,
                output(input)
        );

        State generated = service.get(
                accountId,
                visitId,
                state.id()
        );

        assertThat(generated.generationStatus())
                .isEqualTo("READY");

        assertThat(generated.startAvailable()).isFalse();

        mvc.perform(
                post("/api/visits/" + visitId + "/report")
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
        ).andExpect(status().isConflict());

        jdbc.update("""
                UPDATE app.quiz_sets
                SET status = 'PUBLISHED',
                    reviewed_by = 'test-reviewer',
                    reviewed_at = now(),
                    published_at = now()
                WHERE observation_id = ?
                """, state.id());

        mvc.perform(
                post("/api/visits/" + visitId + "/report")
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
        ).andExpect(status().isOk());

        var response = mvc.perform(
                        get("/api/visits/" + visitId + "/quiz")
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        var progress = mapper.readTree(
                response.getContentAsByteArray()
        );

        assertThat(progress.get("questions").size())
                .isEqualTo(3);

        for (var question : progress.get("questions")) {
            assertThat(question.get("feedback").isNull())
                    .isTrue();

            assertThat(question.has("correctAnswer"))
                    .isFalse();
        }

        for (var question : progress.get("questions")) {
            String questionId = question.get("questionId")
                    .asText();

            mvc.perform(
                    put(
                            "/api/visits/" + visitId
                                    + "/answers/" + questionId
                    )
                            .header(
                                    "Authorization",
                                    "Bearer " + token
                            )
                            .contentType("application/json")
                            .content("{\"answer\":\"O\"}")
            ).andExpect(status().isOk());
        }

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.visits WHERE id = ?",
                String.class,
                visitId
        )).isEqualTo("COMPLETED");
    }

    @Test
    void expiredWorkerCannotPublishOverANewerAttempt()
            throws Exception {
        State state = upload(
                UUID.randomUUID().toString(),
                1
        );

        Lease old = transactions.claim().orElseThrow();

        var snapshot = transactions.snapshot(old);
        Input input = input(snapshot);

        jdbc.update("""
                UPDATE app.observations
                SET lease_expires_at = now() - interval '1 second'
                WHERE id = ?
                """, state.id());

        assertThat(transactions.claim()).isEmpty();

        service.retry(
                accountId,
                visitId,
                state.id(),
                1
        );

        Lease current = transactions.claim().orElseThrow();

        assertThatThrownBy(
                () -> transactions.complete(
                        old,
                        snapshot,
                        input,
                        output(input)
                )
        ).isInstanceOf(GenerationException.class);

        transactions.failed(
                old,
                FailureCode.WORKER_INTERRUPTED
        );

        assertThat(
                service.retry(
                        accountId,
                        visitId,
                        state.id(),
                        1
                ).generationStatus()
        ).isEqualTo("GENERATING");

        transactions.complete(
                current,
                transactions.snapshot(current),
                input,
                output(input)
        );

        assertThat(jdbc.queryForObject(
                """
                SELECT count(*)
                FROM app.quiz_sets
                WHERE observation_id = ?
                """,
                Integer.class,
                state.id()
        )).isEqualTo(1);
    }

    private State upload(String key, int count)
            throws Exception {
        var request = multipart(path())
                .header(
                        "Authorization",
                        "Bearer " + token
                )
                .header("Idempotency-Key", key);

        for (int i = 0; i < count; i++) {
            request.file(photo());
        }

        byte[] body = mvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        return mapper.readValue(body, State.class);
    }

    private MockMultipartFile photo() throws Exception {
        var output = new ByteArrayOutputStream();

        ImageIO.write(
                new BufferedImage(
                        16,
                        16,
                        BufferedImage.TYPE_INT_RGB
                ),
                "png",
                output
        );

        return new MockMultipartFile(
                "files",
                "photo.png",
                "image/png",
                output.toByteArray()
        );
    }

    private Input input(
            ObservationGenerationTransactions.Snapshot snapshot
    ) {
        return new Input(
                snapshot.placeName(),
                snapshot.images().stream()
                        .map(image -> new Photo(
                                image.position(),
                                image.mimeType(),
                                storage.read(image.storageKey())
                        ))
                        .toList(),
                snapshot.sources()
        );
    }

    private Output output(Input input) {
        var source = input.sources().getFirst();

        return new Output(
                "READY",
                "사진과 자료를 관찰해요.",
                java.util.stream.IntStream.rangeClosed(1, 3)
                        .mapToObj(i -> new Question(
                                "시험 명제 " + i,
                                "O",
                                "자료와 일치해요.",
                                source.id(),
                                source.factText(),
                                1,
                                "사진에서 관찰한 시험 근거",
                                "첫 단계 힌트",
                                "둘째 단계 힌트",
                                "안내판을 살펴봐요."
                        ))
                        .toList()
        );
    }

    private String path() {
        return "/api/visits/" + visitId + "/observations";
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory(
                    "podo-observation-test-"
            );
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}