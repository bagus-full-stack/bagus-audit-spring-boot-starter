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
- [Audit assistant (optional)](#audit-assistant-optional)
- [Storage](#storage)
- [Configuration](#configuration)
- [Events, metrics and health](#events-metrics-and-health)
- [Error format](#error-format)
- [Build and test](#build-and-test)
- [Status and known limits](#status-and-known-limits)
- [Releasing to Maven Central](#releasing-to-maven-central)
- [Changelog](#changelog)
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
| `starter-ai` | Optional: [audit assistant](#audit-assistant-optional), questions in plain language answered by a local Ollama model (Spring AI) |
| `sample` | Example application with H2 (default) and PostgreSQL (`prod` profile), Flyway migration included |

Maven coordinates (group `io.github.bagus-full-stack`): `audit-starter`, `audit-starter-jpa`, `audit-starter-ai`.

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

The sample sets `ddl-auto: validate`. The purge job's SQL runs on PostgreSQL and H2 only. With several instances, set `audit.retention.deleted-days` on one of them: every instance with a value above 0 runs the purge at the same time. The starter also turns on Spring scheduling (`@EnableScheduling`) for the whole application, so any `@Scheduled` method of yours that was inactive for lack of it starts running.

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

  Tested with a mock OAuth2 server, a real Keycloak 26 (`docker-compose.keycloak.yml`) and Auth0-style namespaced claims
  (`docker-compose.auth0.yml`); check your own provider's tokens.
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

## Audit assistant (optional)

Add `audit-starter-ai` next to a starter and the log can answer questions in plain language:

```bash
curl -s -X POST localhost:8080/api/audit/ask -H "Authorization: Bearer $READER" -H 'Content-Type: application/json'   -d '{"question":"Which refund was approved for order 42?"}'
# {"answer":"...","sources":["<entry id>"]}
```

It is retrieval-augmented generation (RAG): the question is embedded, the closest entries are found in a vector index,
and the model answers from those entries only. `sources` lists the entries the answer was based on.

- **Needs** an Ollama server (`spring.ai.ollama.base-url`) with a chat model and an embedding model
  (`spring.ai.ollama.chat.options.model`, `spring.ai.ollama.embedding.options.model`), through Spring AI 1.0.3.
  Any other Spring AI chat and embedding model works if you put its starter on the classpath instead.
- **Read-only.** It never writes to the log. The endpoint needs `AUDIT_READ` or `AUDIT_ADMIN`.
- **Same rules as the rest of the API.** The search is limited to the caller's tenant, and every hit is read again
  from the repository, so a deleted or erased entry, or another tenant's, never reaches the model, even if the index
  is behind. Deleting or erasing an entry also removes it from the index.
- **The model runs where you put it.** With a local Ollama the audit data stays on your network. Pointing Spring AI at
  a hosted model sends the question and the matching entries (payloads included) to that provider.
- **Busy:** at most 3 questions run at once per instance; the next one gets `429` until a slot frees up, so one caller
  cannot starve the indexer that shares the model server.
- **Failures** are a `503` problem response; the audit write itself never depends on the model.
- **Index:** you must define a `VectorStore` bean (pgvector, ...) for real use. For a trial, `audit.ai.in-memory-index=true`
  gives an in-memory index: it grows with every audit write, is lost on restart (the latest `audit.ai.backfill` entries,
  default 1000, are indexed again at startup), and each instance only sees its own writes. Without one of the two the
  application does not start. The index is fed in the background from the audit events.
- **Long payloads** are cut before use: to 2 000 characters for the index and 1 000 per entry in the prompt, so one big
  entry cannot push the model's instructions out of its context window.
- **Prompt injection:** payloads are text written by users. The system prompt tells the model to treat entries as
  data, which lowers the risk but does not remove it: a model can still be misled by a crafted payload.

| Property | Default | Meaning |
|---|---|---|
| `audit.ai.enabled` | `true` | Turn the assistant off even when `audit-starter-ai` is present |
| `audit.ai.in-memory-index` | `false` | Allow the default in-memory index (trial only) |
| `audit.ai.top-k` | `8` | Entries handed to the model for one question |
| `audit.ai.backfill` | `1000` | Latest entries indexed at startup; `0` indexes none |

Try it: `docker compose -f docker-compose.yml -f docker-compose.ai.yml up -d --build` then `./docker/ai-test.sh`
(the first start downloads about 1.5 GB of models). The sample only includes the assistant with `-Pai`.

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
- The events go out right after the write. If the write happens inside your own transaction, the built-in metrics and assistant index wait for the commit, so a rolled-back write is not counted; use `@TransactionalEventListener` in your own listeners too. If a listener throws, the error is logged and the request still succeeds.
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

Publishing to Maven Central is described in [Releasing](#releasing-to-maven-central).

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

Same stack with Auth0's claim layout (namespaced `https://app.example.com/roles` and `/tenant`, no Keycloak):

```bash
docker compose -f docker-compose.yml -f docker-compose.auth0.yml up -d --build
TOKEN_URL=http://localhost:8081/auth0/token ./docker/smoke-test.sh
```

Two app instances on one database (writes, cursors across instances, tenants, delete, and the purge job running on both at once):

```bash
docker compose -f docker-compose.yml -f docker-compose.multi.yml up -d --build
./docker/multi-instance-test.sh     # 13 checks
```

The sample's connection pool is 30 (`DATABASE_POOL_SIZE` changes it). The starter sets no pool size: in your own application
it is `spring.datasource.hikari.maximum-pool-size`. Size it to your concurrent requests: with 50 clients, a pool of 10 gave a
p95 of 1.1 s and a pool of 30 gave 0.14 to 0.27 s.

Load test (k6, 30 writers + 20 readers, 40 s; needs tokens in `WRITER` and `READER`): `docker/load-test.js`.

The mock OAuth2 server issues tokens for `admin-acme`, `writer-acme`, `reader-acme` and `reader-globex`
(see `docker/mock-oauth2.json`):

```bash
curl -s -X POST localhost:8081/default/token -d 'grant_type=client_credentials&client_id=reader-acme&client_secret=x'
```

## Status and known limits

- Version is `1.0.0-SNAPSHOT`; the API may still change.
- Tested: H2 and PostgreSQL 16 (Testcontainers), a mock OAuth2 server and Keycloak 26, Auth0-style claims, two instances on one database, the JVM image and the GraalVM
  native image (`docker build --target runtime-native .`, 28/28 smoke checks), and a k6 load run (200 req/s, p95 about
  400 ms, one container, 2M rows). Not tested: more than two instances, a provider other than Keycloak and Auth0-style claims.
- In a native image `@ConditionalOn*` is decided at build time. Tenancy and retention are therefore switched at runtime
  by their properties, but `audit.enabled` and `audit.api.base-path` must be set when the image is built.
- Not implemented yet: rate limiting, caching, a MongoDB adapter (planned for 1.1, see the [Changelog](#changelog)).
- An admin can delete and erase entries, so the log is not tamper-proof; payloads are stored in clear text.

## Releasing to Maven Central

A release is a tag. Pushing `vX.Y.Z` runs `.github/workflows/release.yml`, which sets the version from the tag,
runs the tests, signs the jars with GPG and **uploads** them to the Central Portal. Nothing goes public until you
click *Publish* there (`autoPublish` is off), so the first release can be checked before it is final.

The repository itself stays on `X.Y.Z-SNAPSHOT`; only the CI build sees the release version.

### One-time setup (needs your accounts, so it is not automated)

1. **Central Portal account** at <https://central.sonatype.com>.
2. **Verify the namespace** `io.github.bagus-full-stack`. For a `io.github.<user>` namespace the portal asks you to
   create a temporary public repository with a name it gives you. Do that from the GitHub account that owns it.
3. **Generate a user token** in the portal (*Account → Generate User Token*). You get a username and a password.
4. **Create a GPG key** and publish its public part to a key server (Central checks signatures against it):
   ```bash
   gpg --full-generate-key                                   # RSA 4096, with a passphrase
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>                 # this text is GPG_PRIVATE_KEY
   ```
5. **Add four GitHub secrets** (*Settings → Secrets and variables → Actions*):

   | Secret | Value |
   |---|---|
   | `CENTRAL_USERNAME` | token username from step 3 |
   | `CENTRAL_PASSWORD` | token password from step 3 |
   | `GPG_PRIVATE_KEY` | the armored private key from step 4 |
   | `GPG_PASSPHRASE` | its passphrase |

### Each release

1. Make sure `main` is green (CI and native build).
2. In the [Changelog](#changelog), move the entries under a new `## [X.Y.Z] — date` heading.
3. Tag and push:
   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```
4. When the workflow is green, open <https://central.sonatype.com/publishing/deployments>, check the deployment
   (four components: `audit-spring-boot-starter-parent`, `audit-autoconfigure`, `audit-starter`, `audit-starter-jpa`)
   and press *Publish*.
5. The artifacts appear on Maven Central after the portal validates them (usually within minutes to an hour).
6. Update the version in the README install snippet.

A published version can never be changed or deleted from Central. If the upload is wrong, drop the deployment in the
portal before publishing and tag again with a new version.

### What was checked, and what was not

Checked locally: the `release` profile builds sources and javadoc jars for the three libraries, excludes the sample,
produces a bundle of the four expected components, and signs every file (tested with a throwaway key on Linux).

Not checked: the upload itself, the namespace verification and the real key server, because they need your accounts.
The first tag is the real test.

## Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versioning follows [Semantic Versioning](https://semver.org/).

### [Unreleased]

#### Added
- `audit-starter-ai`: an optional audit assistant, `POST /api/audit/ask`, answering questions about the log with a local Ollama model (RAG through Spring AI)

#### Fixed
- The audit security chain disabled CSRF but still accepted a session cookie from the host's own login. It is now stateless: bearer tokens only
- A host application's own Spring Data repositories were no longer found: the starter's `@EnableJpaRepositories` switched Boot's repository scan off. The audit package is now added to Boot's scan instead
- The tenant id was kept in an `InheritableThreadLocal`, so a pooled thread created during one tenant's request kept that tenant for later tasks. It is now a plain `ThreadLocal`

### [1.0.0] — 2026-10-21

First release. The sections after the feature list record what changed while the code was hardened before publishing.

#### Features
- Multi-module Maven project: `autoconfigure`, `starter`, `starter-jpa`
- Hexagonal architecture: `AuditRepository` port with JPA and in-memory adapters
- Cursor-based pagination via composite keyset `(created_at DESC, id DESC)`
- Soft delete with `@SQLDelete` + `@SQLRestriction`
- GDPR erasure endpoint `DELETE /{id}/personal-data` with `AuditGdprEraser` SPI
- Role-based security: `ROLE_AUDIT_READ`, `ROLE_AUDIT_WRITE`, `ROLE_AUDIT_ADMIN`
- OAuth2 JWT resource server integration
- Multi-tenancy: HEADER, JWT_CLAIM, SUBDOMAIN strategies
- Bulk operations (`POST /batch`, max 100 entries, one transaction)
- Spring Application Events: Created, Deleted, Erased
- Micrometer counters `audit.entries.created`, `audit.entries.deleted`, `audit.entries.erased`
- Actuator health indicator
- GraalVM Native Image hints (`AuditRuntimeHints`)
- RFC 9457 Problem Details error responses
- Flyway migrations for the sample (H2 and PostgreSQL), with keyset and tenant indexes
- GitHub Actions: CI, GraalVM native smoke test, release to Maven Central
- Sample application with H2 (dev) / PostgreSQL (prod) profiles

#### Hardening before release
- `audit.security.issuer` is required with `jwk-set-uri`; `audit.security.principal-claim` sets `created_by`.
- Purge works in batches (`audit.retention.batch-size`); partial index on `deleted_at`.
- Keyset query bounds the index scan (`created_at <= cursor`): 436 ms to 0.16 ms on 2M rows.
- Native image: tenancy and retention are switched at runtime, and the `@Modifying` queries carry their own transaction.
- Docker: Keycloak and native overrides, k6 load script, `restart: on-failure` for the app.
- The in-memory repository needs `audit.in-memory=true`; without it, startup fails when no `AuditRepository` exists.
- `POST /batch` accepts at most 100 entries, to bound memory per request.
- Soft delete and GDPR erasure log the acting user at INFO level.
- README lists every schema script; unused setters removed from `AuditLogEntity`.

#### Release
- `release` Maven profile (sources, javadoc, GPG signing, Central Portal upload) and the [release guide](#releasing-to-maven-central).

#### Added
- Docker: multi-stage `Dockerfile`, `docker-compose.yml` (PostgreSQL + mock OAuth2), `docker/smoke-test.sh`.
- `audit.security.roles-claim` / `audit.security.jwk-set-uri`; JWT roles are now mapped to `ROLE_*`.
- Robustness tests (model, service, tenant filter, in-memory concurrency, REST validation and role matrix),
  sample end-to-end tests on H2 and on PostgreSQL (Testcontainers).

#### Fixed (CI and packaging)
- `mvnw` is now committed as executable (`100755`). Every CI run since the first push failed with
  `./mvnw: Permission denied`; the same build now passes on Linux with JDK 21.
- `Dockerfile`: `HEALTHCHECK` on `/actuator/health` (no curl needed).
- GDPR erasure now increments `version`.
- `audit.security.roles-claim` accepts a nested path (`realm_access.roles`) and exact names with dots.
- A startup warning when `jwk-set-uri` is used without `issuer` and `audience`; the sample's `prod` profile and
  `docker-compose.yml` set them (`JWT_ISSUER`, `JWT_AUDIENCE`).

#### Changed
- **Breaking:** `POST /batch` returns the list of saved entries directly; the `{results, succeeded, failed}` wrapper
  is gone (`failed` was always 0).
- Optional retention: `audit.retention.deleted-days` and `audit.retention.cron` purge old soft-deleted rows.
  New port method `AuditRepository.purgeDeleted(Instant)` (default: does nothing).
- Sample, PostgreSQL: migration `V3` converts the instant columns to `TIMESTAMPTZ` (existing values read as UTC).
  I could not reproduce a cursor error with plain `TIMESTAMP` in a daylight-saving zone, so this is preventive.
- Metrics: the `audit.repository.size` gauge (a full `COUNT(*)` on every scrape) is replaced by the counters
  `audit.entries.created`, `audit.entries.deleted` and `audit.entries.erased`. `AuditService.count()` is removed.
- A listener that throws no longer turns a committed write into an error response; it is logged.

#### Security
- `audit.security.issuer` and `audit.security.audience` validate the token when the decoder is built from `jwk-set-uri`.
- `audit.tenancy.enforce-isolation` now defaults to `true`: with tenancy on, a request without a tenant used to read every tenant's entries.
- With `HEADER` or `SUBDOMAIN`, a tenant that differs from the token's tenant claim is rejected with `403`.
- `DELETE` now clears `payload`, `created_by` and `last_modified_by` (a deleted entry could not be erased afterwards).
  Rows deleted by an earlier version keep their data: run `UPDATE audit_log SET payload = NULL, created_by = NULL, last_modified_by = NULL WHERE deleted_at IS NOT NULL`.

#### Fixed
- The starter now registers `AuditLogEntity` with the host's entity scan (host apps outside `io.github.assami.audit` failed to start).
- Sample: Flyway `V1` no longer uses partial indexes on H2 (vendor scripts under `db/vendor/{vendor}`), prod profile uses the PostgreSQL driver and disables the H2 console, `flyway-database-postgresql` added.

#### Fixed
- `audit-starter` and `audit-starter-jpa` now include the OAuth2 resource server; the security
  configuration needs it, and only `audit-starter-full` had it

#### Removed
- Settings that no code read: `audit.rate-limit.*`, `audit.cache.*`, `audit.async.*`,
  `audit.api.cors.*`, `audit.api.bulk-atomic`, `audit.api.page-size-*`, `audit.soft-delete.*`,
  `audit.security.enabled`, `audit.security.roles.*`
- Unused dependencies: Resilience4j, Caffeine, Redis, Spring Retry, AOP, Spring Cache and Spring Data REST
  (Spring Data REST could export the audit table over HTTP outside the audit security chain).
  The `audit-starter-full` module is removed: it only depended on `audit-starter-jpa`, use that instead
- Unused i18n bundles (`audit-messages*.properties`): no message source ever read them
- `AuditMetrics.recordOperation` (never called) and the `audit.operations.total` /
  `audit.operation.duration` metrics; only `audit.repository.size` remains

#### Planned
- MongoDB adapter

## License

Apache License 2.0, see [LICENSE](LICENSE).
