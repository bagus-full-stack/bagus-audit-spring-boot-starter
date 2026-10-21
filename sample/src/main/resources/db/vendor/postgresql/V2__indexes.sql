-- PostgreSQL: partial indexes skip soft-deleted rows.

CREATE INDEX idx_audit_log_keyset
    ON audit_log (created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_audit_log_service_name
    ON audit_log (service_name)
    WHERE deleted_at IS NULL;

-- One tenant's newest entries, no sort step
CREATE INDEX idx_audit_log_tenant_keyset
    ON audit_log (tenant_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- GDPR scheduled erasure scan
CREATE INDEX idx_audit_log_gdpr
    ON audit_log (gdpr_erased_at)
    WHERE gdpr_erased_at IS NOT NULL;