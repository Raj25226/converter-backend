CREATE TABLE daily_usage (id VARCHAR(80) PRIMARY KEY, day DATE NOT NULL, attempts INTEGER NOT NULL CHECK (attempts >= 0));
CREATE INDEX idx_daily_usage_day ON daily_usage(day);
