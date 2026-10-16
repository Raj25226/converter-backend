package com.converter.service;

import com.converter.exception.AppException;
import com.converter.feature.FeatureFlag;
import com.converter.feature.FeatureFlagSettings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagService {
    private final JdbcTemplate jdbcTemplate;
    private final FeatureFlagSettings featureFlagSettings;

    public record FlagState(
            String key,
            boolean enabled,
            boolean defaultEnabled,
            boolean overridden,
            Instant updatedAt,
            Long updatedBy) {}

    public record Administration(String source, boolean editable, List<FlagState> flags) {}

    public Map<String, Boolean> flags() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (FeatureFlag flag : FeatureFlag.values())
            result.put(
                    flag.key(), featureFlagSettings.getDefaults().getOrDefault(flag.key(), true));
        if (databaseManaged())
            jdbcTemplate.query(
                    "SELECT flag_key, enabled FROM feature_flags",
                    row -> {
                        if (result.containsKey(row.getString(1)))
                            result.put(row.getString(1), row.getBoolean(2));
                    });
        return Collections.unmodifiableMap(result);
    }

    public boolean enabled(FeatureFlag flag) {
        return flags().get(flag.key());
    }

    public void require(FeatureFlag... required) {
        var current = flags();
        for (FeatureFlag flag : required)
            if (!current.get(flag.key()))
                throw new AppException(
                        "This feature is currently disabled: " + flag.key() + ".",
                        HttpStatus.SERVICE_UNAVAILABLE);
    }

    public Administration administration() {
        Map<String, FlagState> states = new LinkedHashMap<>();
        for (FeatureFlag flag : FeatureFlag.values()) {
            boolean defaultEnabled =
                    featureFlagSettings.getDefaults().getOrDefault(flag.key(), true);
            states.put(
                    flag.key(),
                    new FlagState(flag.key(), defaultEnabled, defaultEnabled, false, null, null));
        }
        if (databaseManaged())
            jdbcTemplate.query(
                    "SELECT flag_key, enabled, updated_at, updated_by FROM feature_flags",
                    row -> {
                        String key = row.getString(1);
                        if (states.containsKey(key))
                            states.put(
                                    key,
                                    new FlagState(
                                            key,
                                            row.getBoolean(2),
                                            states.get(key).defaultEnabled(),
                                            true,
                                            row.getTimestamp(3).toInstant(),
                                            row.getObject(4, Long.class)));
                    });
        return new Administration(
                databaseManaged() ? "database" : "environment",
                databaseManaged(),
                List.copyOf(states.values()));
    }

    @Transactional
    public Administration update(String key, boolean enabled, Long actor) {
        FeatureFlag.fromKey(key);
        requireDatabase();
        // A singleton lock serializes first inserts as well as updates across application
        // instances.
        jdbcTemplate.queryForObject(
                "SELECT id FROM feature_flag_admission WHERE id = 1 FOR UPDATE", Integer.class);
        Timestamp now = Timestamp.from(Instant.now());
        int changed =
                jdbcTemplate.update(
                        "UPDATE feature_flags SET enabled = ?, updated_at = ?, updated_by = ? WHERE flag_key = ?",
                        enabled,
                        now,
                        actor,
                        key);
        if (changed == 0)
            jdbcTemplate.update(
                    "INSERT INTO feature_flags(flag_key, enabled, updated_at, updated_by) VALUES (?, ?, ?, ?)",
                    key,
                    enabled,
                    now,
                    actor);
        jdbcTemplate.update(
                "INSERT INTO feature_flag_audit(flag_key, enabled, actor_id, changed_at) VALUES (?, ?, ?, ?)",
                key,
                enabled,
                actor,
                now);
        log.info("Feature flag {} set to {} by administrator {}", key, enabled, actor);
        return administration();
    }

    @Transactional
    public Administration reset(String key, Long actor) {
        FeatureFlag.fromKey(key);
        requireDatabase();
        jdbcTemplate.queryForObject(
                "SELECT id FROM feature_flag_admission WHERE id = 1 FOR UPDATE", Integer.class);
        jdbcTemplate.update("DELETE FROM feature_flags WHERE flag_key = ?", key);
        jdbcTemplate.update(
                "INSERT INTO feature_flag_audit(flag_key, enabled, actor_id, changed_at) VALUES (?, NULL, ?, ?)",
                key,
                actor,
                Timestamp.from(Instant.now()));
        log.info("Feature flag {} reset to environment default by administrator {}", key, actor);
        return administration();
    }

    private boolean databaseManaged() {
        return featureFlagSettings.getSource() == FeatureFlagSettings.Source.DATABASE;
    }

    private void requireDatabase() {
        if (!databaseManaged())
            throw new AppException(
                    "Feature flags are managed by environment configuration.", HttpStatus.CONFLICT);
    }
}
