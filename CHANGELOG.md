# Changelog

All notable changes to this project will be documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

### Fixed
- `audit-starter` and `audit-starter-jpa` now include the OAuth2 resource server; the security
  configuration needs it, and only `audit-starter-full` had it

### Removed
- Settings that no code read: `audit.rate-limit.*`, `audit.cache.*`, `audit.async.*`,
  `audit.api.cors.*`, `audit.api.bulk-atomic`, `audit.api.page-size-*`, `audit.soft-delete.*`,
  `audit.security.enabled`, `audit.security.roles.*`
- Unused dependencies: Resilience4j, Caffeine, Redis, Spring Retry, AOP, Spring Cache and Spring Data REST
  (Spring Data REST could export the audit table over HTTP outside the audit security chain).
  The `audit-starter-full` module is removed: it only depended on `audit-starter-jpa`, use that instead
- Unused i18n bundles (`audit-messages*.properties`): no message source ever read them
- `AuditMetrics.recordOperation` (never called) and the `audit.operations.total` /
  `audit.operation.duration` metrics; only `audit.repository.size` remains

## [1.0.0] — 2024-XX-XX

### Added
- Multi-module Maven project: `autoconfigure`, `starter`, `starter-jpa`
- Hexagonal architecture: `AuditRepository` port with JPA and in-memory adapters
- Cursor-based pagination via composite keyset `(created_at DESC, id DESC)`
- Soft delete with `@SQLDelete` + `@SQLRestriction`
- GDPR erasure endpoint `DELETE /{id}/personal-data` with `AuditGdprEraser` SPI
- Role-based security: `ROLE_AUDIT_READ`, `ROLE_AUDIT_WRITE`, `ROLE_AUDIT_ADMIN`
- OAuth2 JWT resource server integration
- Multi-tenancy: HEADER, JWT_CLAIM, SUBDOMAIN strategies
- Bulk operations (`POST /batch`, max 500 entries, one transaction)
- Spring Application Events: Created, Deleted, Erased
- Micrometer metric: `audit.repository.size`
- Actuator health indicator
- GraalVM Native Image hints (`AuditRuntimeHints`)
- RFC 9457 Problem Details error responses
- Flyway + Liquibase DDL reference (5 indexes)
- GitHub Actions: CI, release to Maven Central, GraalVM native smoke test
- Sample application with H2 (dev) / PostgreSQL (prod) profiles

### Planned (v1.1)
- MongoDB adapter
- Redis distributed rate limiting full implementation
- Distributed cache (Redis) adapter
