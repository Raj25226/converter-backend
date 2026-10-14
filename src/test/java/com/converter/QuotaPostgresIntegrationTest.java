package com.converter;

import com.converter.service.QuotaService;
import com.converter.exception.RateLimitException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "QUOTA_TEST_DATABASE_URL", matches = ".+")
class QuotaPostgresIntegrationTest {
    static JdbcTemplate admin;
    static JdbcTemplate jdbc;
    static QuotaService quotas;
    static String schema = "quota_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
    @BeforeAll static void setup() {
        String url = System.getenv("QUOTA_TEST_DATABASE_URL");
        String user = System.getenv("QUOTA_TEST_DATABASE_USER");
        String password = System.getenv("QUOTA_TEST_DATABASE_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
        jdbc.execute("CREATE TABLE daily_usage (id VARCHAR(80) PRIMARY KEY, day DATE NOT NULL, attempts INTEGER NOT NULL)");
        quotas = new QuotaService(jdbc);
    }
    @AfterAll static void teardown() { if (admin != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    @Test void simultaneousRequestsCannotExceedQuota() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 40; i++) results.add(pool.submit(() -> {
                try { quotas.reserve(100L, null, 2); return true; }
                catch (RateLimitException e) { return false; }
            }));
            int successes = 0;
            for (var result : results) if (result.get(30, TimeUnit.SECONDS)) successes++;
            assertEquals(2, successes); assertEquals(2, quotas.used(100L, null));
        }
    }
    @Test void midnightResetsPreviousDayUsage() {
        quotas.reserve(200L, null, 1);
        assertThrows(RateLimitException.class, () -> quotas.reserve(200L, null, 1));
        jdbc.update("UPDATE daily_usage SET day = ? WHERE id = ?", LocalDate.now(ZoneOffset.UTC).minusDays(1), "user:200");
        quotas.reserve(200L, null, 1);
        assertEquals(1, quotas.used(200L, null));
    }
}
