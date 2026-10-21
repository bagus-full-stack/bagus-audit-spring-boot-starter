-- ============================================================
-- Audit Log — initial schema
-- Version : V1
-- Author  : Assami Baga <bagaassami009@gmail.com>
-- ============================================================

CREATE TABLE audit_log (
    id               VARCHAR(36)   NOT NULL,
    tenant_id        VARCHAR(255),
    service_name     VARCHAR(255)  NOT NULL,
    action           VARCHAR(255)  NOT NULL,
    level            VARCHAR(50)   NOT NULL DEFAULT 'INFO',
    payload          TEXT,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(255),
    last_modified_by VARCHAR(255),
    version          BIGINT        NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMP,
    gdpr_erased_at   TIMESTAMP,

    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

-- Keyset pagination index (supports cursor-based traversal)
CREATE INDEX idx_audit_log_keyset
    ON audit_log (created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- Service name filter
CREATE INDEX idx_audit_log_service_name
    ON audit_log (service_name)
    WHERE deleted_at IS NULL;

-- Multi-tenancy filter
CREATE INDEX idx_audit_log_tenant_id
    ON audit_log (tenant_id)
    WHERE deleted_at IS NULL;

-- GDPR scheduled erasure scan
CREATE INDEX idx_audit_log_gdpr
    ON audit_log (gdpr_erased_at)
    WHERE gdpr_erased_at IS NOT NULL;
