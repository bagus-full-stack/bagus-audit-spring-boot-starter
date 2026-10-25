-- H2 (dev/test): no partial indexes, plain ones with the same names.

CREATE INDEX idx_audit_log_keyset ON audit_log (created_at DESC, id DESC);
CREATE INDEX idx_audit_log_service_name ON audit_log (service_name);
CREATE INDEX idx_audit_log_tenant_keyset ON audit_log (tenant_id, created_at DESC, id DESC);