package com.podo.server.profile.image.cleanup;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProfileImageDeletionMigrationTests {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17.11");

    @Test
    void upgradeFromV5BackfillsOnlyRecoverableOwnershipAndKeepsUnknownRows() {
        var dataSource = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(dataSource).defaultSchema("app").target("5").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID account = UUID.randomUUID();
        UUID image = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app.accounts VALUES (?, 'migration-owner', 'ACTIVE', now(), now())", account);
        jdbc.update("INSERT INTO app.profile_images VALUES (?, ?, ?, 'image/webp', 1, NULL, now())", image, account, key);
        jdbc.update("INSERT INTO app.profile_image_deletions VALUES (?, ?, now())", image, key);
        jdbc.update("INSERT INTO app.profile_image_deletions VALUES (?, ?, now())", unknown, UUID.randomUUID().toString());
        var latest = Flyway.configure().dataSource(dataSource).defaultSchema("app").load();

        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT account_id FROM app.profile_image_deletions WHERE image_id = ?", UUID.class, image)).isEqualTo(account);
        assertThat(jdbc.queryForObject("SELECT account_id FROM app.profile_image_deletions WHERE image_id = ?", UUID.class, unknown)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.profile_image_deletions", Integer.class)).isEqualTo(2);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        latest.validate();
    }
}
