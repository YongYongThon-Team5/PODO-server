package com.podo.server;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
@Transactional
class PodoServerApplicationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Flyway flyway;

    @Autowired
    DataSource dataSource;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void initializesTheDomainSchemaAndDoesNotReapplyTheMigration() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.info().applied())
                .filteredOn(migration -> migration.getVersion() != null)
                .hasSize(2);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'app' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, String.class)).containsExactlyInAnyOrder(
                "accounts", "oauth_login_requests", "auth_sessions", "auth_tokens",
                "child_profiles", "profile_images", "profile_image_deletions",
                "tags", "places", "place_sources",
                "visits", "quiz_sets", "quiz_questions", "quiz_answers", "quiz_hint_views");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE constraint_schema = 'app' AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isEqualTo(18);

        Flyway restartedFlyway = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("app")
                .locations("classpath:db/migration")
                .load();
        assertThat(restartedFlyway.migrate().migrationsExecuted).isZero();
        assertThat(restartedFlyway.info().pending()).isEmpty();
        assertThat(restartedFlyway.info().applied())
                .filteredOn(migration -> migration.getVersion() != null)
                .hasSize(2);
        restartedFlyway.validate();
    }

    @ParameterizedTest
    @CsvSource({
            "PLANNED, PLANNED",
            "PLANNED, QUIZ_IN_PROGRESS",
            "QUIZ_IN_PROGRESS, PLANNED",
            "QUIZ_IN_PROGRESS, QUIZ_IN_PROGRESS"
    })
    void rejectsASecondActiveVisitForTheSameAccount(String existingStatus, String nextStatus) {
        UUID accountId = insertAccount();
        UUID placeId = insertPlace();
        insertVisit(accountId, placeId, existingStatus, UUID.randomUUID().toString());
        insertVisit(insertAccount(), placeId, "PLANNED", UUID.randomUUID().toString());

        assertSqlState("23505", () ->
                insertVisit(accountId, placeId, nextStatus, UUID.randomUUID().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED"})
    void allowsANewActiveVisitAfterATerminalVisit(String terminalStatus) {
        UUID accountId = insertAccount();
        UUID placeId = insertPlace();
        insertVisit(accountId, placeId, terminalStatus, UUID.randomUUID().toString());
        insertVisit(accountId, placeId, "PLANNED", UUID.randomUUID().toString());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.visits WHERE account_id = ?",
                Integer.class, accountId)).isEqualTo(2);
    }

    @Test
    void allowsHistoricalQuizSetsButRejectsASecondPublishedSetForTheSamePlace() {
        UUID placeId = insertPlace();
        insertQuizSet(placeId, 1, "DRAFT");
        insertQuizSet(placeId, 2, "DRAFT");
        insertQuizSet(placeId, 3, "RETIRED");
        insertQuizSet(placeId, 4, "RETIRED");
        insertQuizSet(placeId, 5, "PUBLISHED");
        insertQuizSet(insertPlace(), 1, "PUBLISHED");

        assertSqlState("23505", () -> insertQuizSet(placeId, 6, "PUBLISHED"));
    }

    @Test
    void rejectsReusingAVisitRequestKeyEvenAfterCancellation() {
        UUID accountId = insertAccount();
        UUID placeId = insertPlace();
        String requestKey = UUID.randomUUID().toString();
        insertVisit(accountId, placeId, "CANCELLED", requestKey);
        insertVisit(insertAccount(), placeId, "PLANNED", requestKey);

        assertSqlState("23505", () -> insertVisit(accountId, placeId, "PLANNED", requestKey));
    }

    @Test
    void rejectsATerminalTimestampOnAPlannedVisit() {
        UUID visitId = insertVisit(insertAccount(), insertPlace(), "PLANNED", UUID.randomUUID().toString());

        assertSqlState("23514", () -> jdbc.update(
                "UPDATE app.visits SET cancelled_at = CURRENT_TIMESTAMP WHERE id = ?", visitId));
    }

    private UUID insertAccount() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.accounts (id, kakao_subject, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, id.toString());
        return id;
    }

    private UUID insertPlace() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.places (id, name, environment, status, updated_at)
                VALUES (?, 'Test place', 'UNKNOWN', 'DRAFT', CURRENT_TIMESTAMP)
                """, id);
        return id;
    }

    private UUID insertQuizSet(UUID placeId, int version, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app.quiz_sets (id, place_id, version, status) VALUES (?, ?, ?, ?)",
                id, placeId, version, status);
        return id;
    }

    private UUID insertVisit(UUID accountId, UUID placeId, String status, String requestKey) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        boolean hasQuiz = status.equals("QUIZ_IN_PROGRESS") || status.equals("COMPLETED");
        UUID quizSetId = hasQuiz ? insertQuizSet(placeId, jdbc.queryForObject(
                "SELECT coalesce(max(version), 0) + 1 FROM app.quiz_sets WHERE place_id = ?",
                Integer.class, placeId), "DRAFT") : null;
        jdbc.update("""
                INSERT INTO app.visits (id, account_id, place_id, planned_date, source, status,
                    quiz_set_id, create_request_key, create_request_hash, reported_at, completed_at,
                    cancelled_at, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_DATE, 'MAP', ?, ?, ?, 'test-request-hash', ?, ?, ?, ?, ?)
                """, id, accountId, placeId, status, quizSetId, requestKey,
                hasQuiz ? now : null, status.equals("COMPLETED") ? now : null,
                status.equals("CANCELLED") ? now : null, now, now);
        return id;
    }

    private void assertSqlState(String expectedState, Runnable statement) {
        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, statement::run);
        assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
        assertThat(((SQLException) exception.getMostSpecificCause()).getSQLState()).isEqualTo(expectedState);
    }

}
