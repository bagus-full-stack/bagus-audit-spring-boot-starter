-- ============================================================
-- Audit Log â€” initial schema
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
