INSERT INTO feature_flags (flag_key, enabled, updated_at, updated_by)
SELECT 'conversion-image-compressor', TRUE, CURRENT_TIMESTAMP, NULL
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE flag_key = 'conversion-image-compressor'
);
