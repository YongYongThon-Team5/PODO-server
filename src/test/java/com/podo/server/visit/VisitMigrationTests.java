package com.podo.server.visit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class VisitMigrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11");

    @Test
    void upgradeFromV2PreservesExistingPlaceVisitAndQuiz() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );

        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("app")
                .target("2")
                .load()
                .migrate();

        var jdbc = new JdbcTemplate(dataSource);

        jdbc.update("""
                INSERT INTO app.accounts VALUES
                  ('10000000-0000-4000-8000-000000000001',
                   'migration-test', 'ACTIVE', now(), now())
                """);

        jdbc.update("""
                INSERT INTO app.places (
                    id,
                    name,
                    environment,
                    status,
                    updated_at
                )
                VALUES (
                    '10000000-0000-4000-8000-000000000002',
                    '기존 장소',
                    'UNKNOWN',
                    'DRAFT',
                    now()
                )
                """);

        jdbc.update("""
                INSERT INTO app.quiz_sets (
                    id,
                    place_id,
                    version,
                    status
                )
                VALUES (
                    '10000000-0000-4000-8000-000000000003',
                    '10000000-0000-4000-8000-000000000002',
                    1,
                    'PUBLISHED'
                )
                """);

        jdbc.update("""
                INSERT INTO app.visits (
                    id,
                    account_id,
                    place_id,
                    planned_date,
                    source,
                    status,
                    quiz_set_id,
                    create_request_key,
                    create_request_hash,
                    reported_at,
                    created_at,
                    updated_at
                )
                VALUES (
                    '10000000-0000-4000-8000-000000000004',
                    '10000000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000002',
                    '2026-11-07',
                    'MAP',
                    'QUIZ_IN_PROGRESS',
                    '10000000-0000-4000-8000-000000000003',
                    'migration-request',
                    'original-hash',
                    now(),
                    now(),
                    now()
                )
                """);

        var latest = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("app")
                .load();

        // V2 다음에는 V3, V4, V5, V6 네 개가 적용된다.
        assertThat(
                latest.migrate().migrationsExecuted
        ).isEqualTo(4);

        latest.validate();

        assertThat(
                jdbc.queryForObject(
                        "SELECT name FROM app.places",
                        String.class
                )
        ).isEqualTo("기존 장소");

        assertThat(
                jdbc.queryForObject(
                        "SELECT status FROM app.visits",
                        String.class
                )
        ).isEqualTo("QUIZ_IN_PROGRESS");

        assertThat(
                jdbc.queryForObject(
                        "SELECT create_request_hash FROM app.visits",
                        String.class
                )
        ).isEqualTo("original-hash");

        assertThat(
                jdbc.queryForObject(
                        "SELECT status FROM app.quiz_sets",
                        String.class
                )
        ).isEqualTo("PUBLISHED");

        assertThat(
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM app.quiz_sets
                        WHERE observation_id IS NULL
                        """,
                        Integer.class
                )
        ).isEqualTo(1);

        assertThat(
                latest.migrate().migrationsExecuted
        ).isZero();
    }
}