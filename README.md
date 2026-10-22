# Audit Spring Boot Starter

A Spring Boot 3 starter that adds an audit-log REST API to any service: write audit entries,
query them with cursor pagination, soft-delete them and erase personal data on request (GDPR).
Storage is pluggable (JPA, or an in-memory fallback for development).

- Java 21, Spring Boot 3.3.4
- Apache License 2.0
- Status: `1.0.0-SNAPSHOT`, not yet published to Maven Central (see [Status](#status-and-known-limits))

## Contents

- [Features](#features)
- [Modules](#modules)
- [Quick start](#quick-start)
- [REST API](#rest-api)
- [Security](#security)
- [Multi-tenancy](#multi-tenancy)
- [GDPR erasure](#gdpr-erasure)
- [Storage](#storage)
- [Configuration](#configuration)
- [Events, metrics and health](#events-metrics-and-health)
- [Error format](#error-format)
- [Build and test](#build-and-test)
- [Status and known limits](#status-and-known-limits)
- [License](#license)

## Features

- REST endpoints to create (single or batch), list, read and delete audit entries
- Cursor (keyset) pagination on `(created_at DESC, id DESC)`: stable under concurrent writes
- Role-based access with OAuth2 JWT: `AUDIT_READ`, `AUDIT_WRITE`, `AUDIT_ADMIN`
- The actor of an entry is the authenticated user, never a value sent by the client
- Optional multi-tenancy: tenant from a header, a JWT claim or the sub-domain
- Soft delete (`deleted_at`) and GDPR erasure of personal fields
- Spring application events, a Micrometer gauge and an Actuator health indicator
- RFC 9457 problem-detail error responses
- Hexagonal design: the `AuditRepository` port has a JPA adapter and an in-memory adapter

## Modules

| Module | Purpose |
|---|---|
| `autoconfigure` | All the code: model, service, REST controller, security, tenancy, JPA and in-memory adapters, auto-configuration |
| `starter` | Core starter: autoconfigure + web, security, OAuth2 resource server, actuator, validation. In-memory repository only with `audit.in-memory=true` |
| `starter-jpa` | `starter` + Spring Data JPA. Persists entries in the `audit_log` table |
| `sample` | Example application with H2 (default) and PostgreSQL (`prod` profile), Flyway migration included |

Maven coordinates (group `io.github.bagus-full-stack`): `audit-starter`, `audit-starter-jpa`.

## Quick start

The project is not on Maven Central yet. Install it into your local repository first:

```bash
git clone https://github.com/bagus-full-stack/bagus-audit-spring-boot-starter.git
cd bagus-audit-spring-boot-starter
./mvnw install
```

Then add the starter to your application:

```xml
<dependency>
  <groupId>io.github.bagus-full-stack</groupId>
  <artifactId>audit-starter-jpa</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Minimal `application.yml`:

```yaml
audit:
  name: order-service        # written as service_name on every entry
  security:
    jwk-set-uri: https://issuer.example.com/.well-known/jwks.json   # only if your app defines no JwtDecoder
    issuer: https://issuer.example.com   # required with jwk-set-uri
    audience: audit-api                  # recommended
```

Create the `audit_log` table with **all** the scripts below, in order (Flyway format; adapt them for Liquibase or
another tool). The indexes are not optional: without them, listing and purging scan the whole table.

| Script (under `sample/src/main/resources/db/`) | Content |
|---|---|
| `migration/V1__create_audit_log.sql` | table |
| `vendor/<h2 or postgresql>/V2__indexes.sql` | keyset and tenant indexes |
| `vendor/postgresql/V3__timestamptz.sql` | PostgreSQL only: `TIMESTAMPTZ` columns |
| `vendor/h2/V3__deleted_index.sql`, `vendor/postgresql/V4__deleted_index.sql` | index used by the purge job |

The sample sets `ddl-auto: validate`. The purge job's SQL runs on PostgreSQL and H2 only. With several instances, set `audit.retention.deleted-days` on one of them: every instance with a value above 0 runs the purge at the same time.

## REST API

Base path: `/api/audit` (change it with `audit.api.base-path`).

| Method and path | Role | Result |
|---|---|---|
| `POST /` | `AUDIT_WRITE` or `AUDIT_ADMIN` | `201` with the created entry |
| `POST /batch` | `AUDIT_WRITE` or `AUDIT_ADMIN` | `201` with the list of saved entries; 1 to 100 entries in one transaction |
| `GET /` | `AUDIT_READ` or `AUDIT_ADMIN` | `200` page of entries |
| `GET /{id}` | `AUDIT_READ` or `AUDIT_ADMIN` | `200` entry, or `404` |
| `DELETE /{id}` | `AUDIT_ADMIN` | `204`, soft delete |
| `DELETE /{id}/personal-data` | `AUDIT_ADMIN` | `204`, GDPR erasure |

Create an entry:

```bash
curl -X POST http://localhost:8080/api/audit \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"action":"ORDER_PLACED","level":"INFO","payload":"{\"orderId\":42}"}'
```

Request fields: `action` (required, max 255), `level` (`DEBUG`, `INFO`, `WARN`, `ERROR`, `CRITICAL`; default `INFO`),
`payload` (free text, max 65 536 characters). The batch body is `{"entries":[ ... ]}`.

List entries (all filters are optional):

```
GET /api/audit?serviceName=order-service&action=ORDER_PLACED&level=INFO
               &from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z&size=50&cursor=...
```

`size` is 1 to 200 (default 20). The response is
`{"content":[...],"nextCursor":"...","hasNext":true}`. Pass `nextCursor` as `cursor` to get the next page.
Cursors are opaque; a malformed one gives `400`.

## Security

- Endpoints are protected by a dedicated filter chain on the base path, plus `@PreAuthorize` on each method.
- Authentication is an OAuth2 resource server (JWT). Roles are read as `ROLE_AUDIT_READ`, `ROLE_AUDIT_WRITE`, `ROLE_AUDIT_ADMIN`.
- The `JwtDecoder` comes from your application. If you define none, set `audit.security.jwk-set-uri`.
  With `jwk-set-uri` the application refuses to start unless `audit.security.issuer` is set: otherwise a token the
  identity provider signed for another application would pass. Set `audit.security.audience` too (a warning is logged without it).
- `audit.security.roles-claim` names the claim that holds the roles. It is read by its exact name first, then as a
  dotted path into nested objects. A list or a space-separated string both work.

| Identity provider | `roles-claim` | Note |
|---|---|---|
| Default / most custom setups | `roles` | |
| Keycloak (realm roles) | `realm_access.roles` | nested path |
| Auth0 | your namespaced claim, e.g. `https://app.example.com/roles` | exact name, dots allowed |

  Tested with a mock OAuth2 server and a real Keycloak 26 (`docker-compose.keycloak.yml`); check your own provider's tokens.
- `audit.security.principal-claim` (default `sub`) picks the claim stored as `created_by`. Keycloak's `sub` is a UUID: use `preferred_username`.
  With neither, the application starts, logs a warning, and every audit request is rejected.
- Spring Boot drops its default security once any `SecurityFilterChain` exists. The starter adds a fallback chain
  (every other route requires authentication, HTTP basic and form login) to keep that default. Define your own chains
  with an `@Order` to override it.
- CSRF is disabled on the audit path (stateless JWT API).

## Multi-tenancy

Disabled by default. Enable it with `audit.tenancy.enabled=true`.

| Strategy | Tenant comes from |
|---|---|
| `JWT_CLAIM` (default) | Claim `audit.tenancy.jwt-claim` (default `tenant_id`) of the authenticated JWT |
| `HEADER` | Request header `audit.tenancy.header-name` (default `X-Tenant-ID`). The caller chooses the value: use it only behind a gateway that sets it. A token without the tenant claim lets its holder name any tenant. If the token also has the tenant claim, the two must match, else `403` |
| `SUBDOMAIN` | First label of the host name (`acme.example.com` gives `acme`). Same claim check as `HEADER` |

When a tenant is resolved, the service forces it on every operation: new entries get it, list queries are filtered by it
(a `tenantId` request parameter is ignored), and reading, deleting or erasing another tenant's entry returns `404`.
By default (`audit.tenancy.enforce-isolation=true`), an audit request without a tenant is rejected with `400`.
Setting it to `false` lets such a request see every tenant's entries.
The filter only applies to the audit base path.

## GDPR erasure

`DELETE /api/audit/{id}/personal-data` sets `payload`, `created_by` and `last_modified_by` to `null` and records
`gdpr_erased_at`. The entry itself stays, so the audit trail keeps its shape.
`DELETE /api/audit/{id}` clears the same three fields as it marks the entry deleted, because a deleted entry
can no longer be reached to erase it.

To run your own logic first (for example, purge a copy in another system), declare a bean implementing `AuditGdprEraser`:

```java
@Component
class MyEraser implements AuditGdprEraser {
    @Override public void beforeErase(String auditLogId) { /* ... */ }
}
```

## Storage

- **JPA** (`starter-jpa`): table `audit_log`, soft delete with `@SQLDelete` and `@SQLRestriction`, optimistic `version` column.
- **In-memory** (`starter` only): used only when `audit.in-memory=true` and no other `AuditRepository` bean exists. Without that property, the application does not start without a repository. All data is lost on restart and
  memory grows without limit. Development only.
- To use another store, define your own `AuditRepository` bean (the port is in `io.github.assami.audit.port`).
- **Retention**: soft-deleted rows stay forever by default. Set `audit.retention.deleted-days` to a number above 0
  and a job (cron `audit.retention.cron`, default `0 30 3 * * *`) removes rows deleted longer ago than that. It is
  batches of `audit.retention.batch-size` rows (default 5000), each in its own transaction, for all tenants.
- **Time zones (PostgreSQL)**: the sample's migration `V3` stores instants as `TIMESTAMPTZ`. It reads existing values
  as UTC, so check that assumption before applying it to data written with another session time zone.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `audit.enabled` | `true` | Master switch for the whole starter |
| `audit.name` | `unknown-service` | Written as `service_name` on every entry |
| `audit.api.base-path` | `/api/audit` | Base path of the REST API |
| `audit.retention.deleted-days` | `0` | Days to keep soft-deleted entries; `0` keeps them forever |
| `audit.retention.cron` | `0 30 3 * * *` | When the purge job runs (only if `deleted-days` is above 0) |
| `audit.retention.batch-size` | `5000` | Rows removed per statement by the purge job |
| `audit.security.jwk-set-uri` | none | JWKS URL used when the application defines no `JwtDecoder` |
| `audit.security.issuer` | none | Required `iss` claim; **required** when `jwk-set-uri` is used |
| `audit.security.principal-claim` | `sub` | Claim stored as `created_by` (Keycloak: `preferred_username`) |
| `audit.security.audience` | none | Required value in the `aud` claim (same limit) |
| `audit.tenancy.enabled` | `false` | Turn multi-tenancy on |
| `audit.tenancy.strategy` | `JWT_CLAIM` | `JWT_CLAIM`, `HEADER` or `SUBDOMAIN` |
| `audit.tenancy.header-name` | `X-Tenant-ID` | Header read by `HEADER` |
| `audit.tenancy.jwt-claim` | `tenant_id` | Claim read by `JWT_CLAIM` |
| `audit.tenancy.enforce-isolation` | `true` | Reject audit requests that have no tenant |

## Events, metrics and health

- Application events: `AuditLogCreatedEvent`, `AuditLogDeletedEvent`, `AuditLogErasedEvent`.
- Micrometer counters `audit.entries.created`, `audit.entries.deleted` and `audit.entries.erased`. They count
  events in memory (since the last restart) and never query the database.
- Event listeners run after the row is committed. If one throws, the error is logged and the request still succeeds.
- Actuator health indicator `auditHealthIndicator`: a primary-key lookup, cheap on large tables.

## Error format

Errors use RFC 9457 `application/problem+json` with extra `errorCode` and `timestamp` fields.
Examples: `404` unknown entry, `400` invalid cursor or missing tenant, `401` no valid token, `403` missing role.

## Build and test

```bash
./mvnw verify
```

Requires JDK 21 or newer. The suite covers service logic, model invariants, the in-memory repository
(random colliding timestamps, concurrent writers), the tenant filter, the REST layer (validation, role matrix,
hostile input), and the sample app end to end on H2. Seven more tests run the same scenarios on a real
PostgreSQL 16 through Testcontainers; they are skipped when Docker is not running.

Publishing to Maven Central is described in [RELEASING.md](RELEASING.md).

## Docker

```bash
docker build --target test .        # whole test suite in a container
docker compose up -d --build        # PostgreSQL + mock OAuth2 (:8081) + sample app (:8080)
./docker/smoke-test.sh              # 28 end-to-end checks (needs bash and curl)
docker compose down -v
```

Same stack with a real Keycloak (roles in `realm_access.roles`, tenant and audience from client mappers):

```bash
docker compose -f docker-compose.yml -f docker-compose.keycloak.yml up -d --build
KEYCLOAK_TOKEN_URL=http://localhost:8082/realms/audit/protocol/openid-connect/token ./docker/smoke-test.sh
```

Load test (k6, 30 writers + 20 readers, 40 s; needs tokens in `WRITER` and `READER`): `docker/load-test.js`.

The mock OAuth2 server issues tokens for `admin-acme`, `writer-acme`, `reader-acme` and `reader-globex`
(see `docker/mock-oauth2.json`):

```bash
curl -s -X POST localhost:8081/default/token -d 'grant_type=client_credentials&client_id=reader-acme&client_secret=x'
```

## Status and known limits

- Version is `1.0.0-SNAPSHOT`; the API may still change.
- Tested: H2 and PostgreSQL 16 (Testcontainers), a mock OAuth2 server and Keycloak 26, the JVM image and the GraalVM
  native image (`docker build --target runtime-native .`, 28/28 smoke checks), and a k6 load run (200 req/s, p95 about
  400 ms, one container, 2M rows). Not tested: several instances, a provider other than Keycloak.
- In a native image `@ConditionalOn*` is decided at build time. Tenancy and retention are therefore switched at runtime
  by their properties, but `audit.enabled` and `audit.api.base-path` must be set when the image is built.
- Not implemented yet: rate limiting, caching, a MongoDB adapter (planned for 1.1, see [CHANGELOG](CHANGELOG.md)).
- An admin can delete and erase entries, so the log is not tamper-proof; payloads are stored in clear text.

## License

Apache License 2.0, see [LICENSE](LICENSE).
