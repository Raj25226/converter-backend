package com.converter;

import static org.junit.jupiter.api.Assertions.*;

import com.converter.feature.FeatureFlag;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FeatureFlagMigrationTest {
    private static final Set<String> DISABLED =
            Set.of(
                    "login",
                    "signup",
                    "google-oauth",
                    "email-verification",
                    "password-reset",
                    "password-change",
                    "profile",
                    "history");

    @Test
    void freshDatabaseHasAllRequestedDefaults() {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:flag-defaults-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;NON_KEYWORDS=DAY;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbcTemplate = new JdbcTemplate(dataSource);
        assertEquals(
                FeatureFlag.values().length,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feature_flags", Integer.class));
        for (FeatureFlag flag : FeatureFlag.values()) {
            assertEquals(
                    !DISABLED.contains(flag.key()),
                    jdbcTemplate.queryForObject(
                            "SELECT enabled FROM feature_flags WHERE flag_key = ?",
                            Boolean.class,
                            flag.key()),
                    flag.key());
        }
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM feature_flags WHERE updated_at IS NULL OR updated_by IS NOT NULL",
                        Integer.class));
    }

    @Test
    void upgradePreservesExistingAdministratorChoices() {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:flag-upgrade-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;NON_KEYWORDS=DAY;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        Flyway.configure().dataSource(dataSource).target("6").load().migrate();
        var jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update(
                "INSERT INTO feature_flags VALUES ('login', TRUE, CURRENT_TIMESTAMP, 42)");
        jdbcTemplate.update(
                "INSERT INTO feature_flags VALUES ('conversion-pdf-to-word', FALSE, CURRENT_TIMESTAMP, 42)");
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertTrue(
                jdbcTemplate.queryForObject(
                        "SELECT enabled FROM feature_flags WHERE flag_key = 'login'",
                        Boolean.class));
        assertFalse(
                jdbcTemplate.queryForObject(
                        "SELECT enabled FROM feature_flags WHERE flag_key = 'conversion-pdf-to-word'",
                        Boolean.class));
        assertEquals(
                2,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM feature_flags WHERE updated_by = 42", Integer.class));
        assertFalse(
                jdbcTemplate.queryForObject(
                        "SELECT enabled FROM feature_flags WHERE flag_key = 'signup'",
                        Boolean.class));
        assertEquals(
                FeatureFlag.values().length,
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feature_flags", Integer.class));
    }
}
