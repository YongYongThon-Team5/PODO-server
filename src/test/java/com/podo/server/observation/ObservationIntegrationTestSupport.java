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
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Testcontainers
abstract class ObservationIntegrationTestSupport {
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

    protected UUID accountId;
    protected String token;
    protected UUID visitId;

    protected final String fact =
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

    protected State upload(String key, int count)
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

    protected MockMultipartFile photo() throws Exception {
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

    protected Input input(
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

    protected Output output(Input input) {
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

    protected String path() {
        return "/api/visits/" + visitId + "/observations";
    }

    protected static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory(
                    "podo-observation-test-"
            );
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
