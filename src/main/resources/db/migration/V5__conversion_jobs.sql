CREATE TABLE conversion_job_admission (id INTEGER PRIMARY KEY);
INSERT INTO conversion_job_admission(id) VALUES (1);
CREATE TABLE conversion_jobs (
 id VARCHAR(32) PRIMARY KEY,
 owner_key VARCHAR(100) NOT NULL,
 request_id VARCHAR(64) NOT NULL,
 user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
 session_id VARCHAR(255), ip_address VARCHAR(45),
 tool VARCHAR(100) NOT NULL, original_name VARCHAR(500) NOT NULL,
 status VARCHAR(20) NOT NULL, worker_id VARCHAR(64),
 result_json TEXT, error_message VARCHAR(1000),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT uq_conversion_job_request UNIQUE(owner_key, request_id)
);
CREATE INDEX idx_jobs_status_created ON conversion_jobs(status, created_at, id);
CREATE INDEX idx_jobs_owner_created ON conversion_jobs(user_id, created_at DESC, id DESC);
CREATE INDEX idx_jobs_expiry ON conversion_jobs(expires_at);
