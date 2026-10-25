package io.github.assami.audit.sample;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stands in for a host application's own entity; reuses the audit_log table so schema validation passes. */
@Entity
@Table(name = "audit_log")
class HostEntity {
    @Id String id;
}
