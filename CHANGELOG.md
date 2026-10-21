# Changelog

All notable changes to this project will be documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

### Added
- Docker: multi-stage `Dockerfile`, `docker-compose.yml` (PostgreSQL + mock OAuth2), `docker/smoke-test.sh`.
- `audit.security.roles-claim` / `audit.security.jwk-set-uri`; JWT roles are now mapped to `ROLE_*`.
- Robustness tests (model, service, tenant filter, in-memory concurrency, REST validation and role matrix),
  sample end-to-end tests on H2 and on PostgreSQL (Testcontainers).

### Fixed (CI and packaging)
- `mvnw` is now committed as executable (`100755`). Every CI run since the first push failed with
  `./mvnw: Permission denied`; the same build now passes on Linux with JDK 21.
- `Dockerfile`: `HEALTHCHECK` on `/actuator/health` (no curl needed).
- GDPR erasure now increments `version`.
- `audit.security.roles-claim` accepts a nested path (`realm_access.roles`) and exact names with dots.
- A startup warning when `jwk-set-uri` is used without `issuer` and `audience`; the sample's `prod` profile and
  `docker-compose.yml` set them (`JWT_ISSUER`, `JWT_AUDIENCE`).

### Changed
- **Breaking:** `POST /batch` returns the list of saved entries directly; the `{results, succeeded, failed}` wrapper
  is gone (`failed` was always 0).
- Optional retention: `audit.retention.deleted-days` and `audit.retention.cron` purge old soft-deleted rows.
  New port method `AuditRepository.purgeDeleted(Instant)` (default: does nothing).
- Sample, PostgreSQL: migration `V3` converts the instant columns to `TIMESTAMPTZ` (existing values read as UTC).
  I could not reproduce a cursor error with plain `TIMESTAMP` in a daylight-saving zone, so this is preventive.
- Metrics: the `audit.repository.size` gauge (a full `COUNT(*)` on every scrape) is replaced by the counters
  `audit.entries.created`, `audit.entries.deleted` and `audit.entries.erased`. `AuditService.count()` is removed.
- A listener that throws no longer turns a committed write into an error response; it is logged.

### Security
- `audit.security.issuer` and `audit.security.audience` validate the token when the decoder is built from `jwk-set-uri`.
- `audit.tenancy.enforce-isolation` now defaults to `true`: with tenancy on, a request without a tenant used to read every tenant's entries.
- With `HEADER` or `SUBDOMAIN`, a tenant that differs from the token's tenant claim is rejected with `403`.
- `DELETE` now clears `payload`, `created_by` and `last_modified_by` (a deleted entry could not be erased afterwards).
  Rows deleted by an earlier version keep their data: run `UPDATE audit_log SET payload = NULL, created_by = NULL, last_modified_by = NULL WHERE deleted_at IS NOT NULL`.

### Fixed
- The starter now registers `AuditLogEntity` with the host's entity scan (host apps outside `io.github.assami.audit` failed to start).
- Sample: Flyway `V1` no longer uses partial indexes on H2 (vendor scripts under `db/vendor/{vendor}`), prod profile uses the PostgreSQL driver and disables the H2 console, `flyway-database-postgresql` added.

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
