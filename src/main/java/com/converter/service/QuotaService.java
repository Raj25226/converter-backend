package com.converter.service;

import com.converter.exception.RateLimitException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@lombok.extern.slf4j.Slf4j
public class QuotaService {
    @Value("${quota.request-window-seconds}")
    private long requestWindowSeconds;

    @Value("${quota.retention-days}")
    private long retentionDays;

    private final JdbcTemplate jdbcTemplate;

    public QuotaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private String key(Long userId, String ip) {
        if (userId != null) return "user:" + userId;
        try {
            return "guest:"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    String.valueOf(ip)
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void reserve(Long userId, String ip, int limit) {
        reserveKey(key(userId, ip), limit);
    }

    public void reserveRequest(String ip, String scope, int limit) {
        String bucket =
                scope
                        + ":"
                        + key(
                                        null,
                                        ip
                                                + ":"
                                                + Instant.now().getEpochSecond()
                                                        / requestWindowSeconds)
                                .substring(6);
        try {
            reserveKey(bucket, limit);
        } catch (RateLimitException e) {
            throw new RateLimitException(
                    "Too many requests. Please retry after the request window resets.");
        }
    }

    private void reserveKey(String id, int limit) {
        // PostgreSQL atomically reserves attempts across simultaneous requests and replicas.
        int changed =
                jdbcTemplate.update(
                        """
            INSERT INTO daily_usage (id, day, attempts) VALUES (?, ?, 1)
            ON CONFLICT (id) DO UPDATE SET day = EXCLUDED.day,
                attempts = CASE WHEN daily_usage.day = EXCLUDED.day THEN daily_usage.attempts + 1 ELSE 1 END
            WHERE daily_usage.day <> EXCLUDED.day OR daily_usage.attempts < ?
            """,
                        id,
                        LocalDate.now(ZoneOffset.UTC),
                        limit);
        if (changed == 0) {
            log.warn(
                    "Quota exhausted for bucket type {} with limit {}",
                    id.substring(0, id.indexOf(':')),
                    limit);
            throw new RateLimitException("Daily limit reached. Limits reset at midnight UTC.");
        }
    }

    public int used(Long userId, String ip) {
        var counts =
                jdbcTemplate.queryForList(
                        "SELECT attempts FROM daily_usage WHERE id = ? AND day = ?",
                        Integer.class,
                        key(userId, ip),
                        LocalDate.now(ZoneOffset.UTC));
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    @Scheduled(fixedDelayString = "${quota.cleanup-interval-ms}")
    public void cleanup() {
        int removed =
                jdbcTemplate.update(
                        "DELETE FROM daily_usage WHERE day < ?",
                        LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays));
        log.debug("Quota cleanup removed {} buckets", removed);
    }
}
