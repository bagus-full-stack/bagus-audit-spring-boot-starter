-- H2: plain index with the same name as on PostgreSQL.
CREATE INDEX idx_audit_log_deleted ON audit_log (deleted_at);
