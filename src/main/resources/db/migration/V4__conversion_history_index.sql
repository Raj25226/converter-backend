CREATE INDEX idx_conversions_user_created_id ON conversions(user_id, created_at DESC, id DESC);
