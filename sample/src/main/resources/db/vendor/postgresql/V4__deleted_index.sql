-- PostgreSQL: the purge job looks up soft-deleted rows by age; index only those rows.
CREATE INDEX idx_audit_log_deleted ON audit_log (deleted_at) WHERE deleted_at IS NOT NULL;
