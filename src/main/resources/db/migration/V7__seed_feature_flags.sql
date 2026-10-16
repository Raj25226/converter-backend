-- Seed missing flags; preserve choices already saved by an administrator.
INSERT INTO feature_flags (flag_key, enabled, updated_at, updated_by)
SELECT defaults.flag_key, defaults.enabled, CURRENT_TIMESTAMP, NULL
FROM (VALUES
    ('login', FALSE),
    ('signup', FALSE),
    ('google-oauth', FALSE),
    ('email-verification', FALSE),
    ('password-reset', FALSE),
    ('profile', FALSE),
    ('password-change', FALSE),
    ('history', FALSE),
    ('analytics-tracking', TRUE),
    ('analytics-dashboard', TRUE),
    ('downloads', TRUE),
    ('background-jobs', TRUE),
    ('conversions', TRUE),
    ('conversion-pdf-to-word', TRUE),
    ('conversion-word-to-pdf', TRUE),
    ('conversion-pdf-to-excel', TRUE),
    ('conversion-excel-to-pdf', TRUE),
    ('conversion-ppt-to-pdf', TRUE),
    ('conversion-jpg-to-png', TRUE),
    ('conversion-png-to-jpg', TRUE),
    ('conversion-jpg-to-webp', TRUE),
    ('conversion-webp-to-png', TRUE),
    ('conversion-heic-to-jpg', TRUE),
    ('conversion-mp4-to-mp3', TRUE),
    ('conversion-mp4-to-gif', TRUE),
    ('conversion-wav-to-mp3', TRUE),
    ('conversion-zip-extract', TRUE),
    ('conversion-file-compress', TRUE)
) AS defaults(flag_key, enabled)
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags existing WHERE existing.flag_key = defaults.flag_key
);
