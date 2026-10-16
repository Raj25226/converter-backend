package com.converter;

import static org.junit.jupiter.api.Assertions.*;

import com.converter.exception.RateLimitException;
import com.converter.service.QuotaService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "QUOTA_TEST_DATABASE_URL", matches = ".+")
class QuotaPostgresIntegrationTest {
    static JdbcTemplate adminJdbcTemplate;
    static JdbcTemplate jdbcTemplate;
    static QuotaService quotaService;
    static String schema = "quota_test_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    static void setup() {
        String url = System.getenv("QUOTA_TEST_DATABASE_URL");
        String user = System.getenv("QUOTA_TEST_DATABASE_USER");
        String password = System.getenv("QUOTA_TEST_DATABASE_PASSWORD");
        adminJdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        adminJdbcTemplate.execute("CREATE SCHEMA " + schema);
        jdbcTemplate =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                                user,
                                password));
        jdbcTemplate.execute(
                "CREATE TABLE daily_usage (id VARCHAR(80) PRIMARY KEY, day DATE NOT NULL, attempts INTEGER NOT NULL)");
        quotaService = new QuotaService(jdbcTemplate);
    }

    @AfterAll
    static void teardown() {
        if (adminJdbcTemplate != null)
            adminJdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void simultaneousRequestsCannotExceedQuota() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 40; i++)
                results.add(
                        pool.submit(
                                () -> {
                                    try {
                                        quotaService.reserve(100L, null, 2);
                                        return true;
                                    } catch (RateLimitException e) {
                                        return false;
                                    }
                                }));
            int successes = 0;
            for (var result : results) if (result.get(30, TimeUnit.SECONDS)) successes++;
            assertEquals(2, successes);
            assertEquals(2, quotaService.used(100L, null));
        }
    }

    @Test
    void midnightResetsPreviousDayUsage() {
        quotaService.reserve(200L, null, 1);
        assertThrows(RateLimitException.class, () -> quotaService.reserve(200L, null, 1));
        jdbcTemplate.update(
                "UPDATE daily_usage SET day = ? WHERE id = ?",
                LocalDate.now(ZoneOffset.UTC).minusDays(1),
                "user:200");
        quotaService.reserve(200L, null, 1);
        assertEquals(1, quotaService.used(200L, null));
    }
}
