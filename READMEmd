# EBANK: Project Summary

A learning project to practice advanced backend, frontend, DevSecOps and observability concepts on a realistic banking domain.

**Decision:** start as a **modular monolith**, then extract services (event-driven, agentic) only when there is a real need.

---

## 1. Domains

| Domain | Responsibility | Phase |
|---|---|---|
| `auth` | Keycloak integration: JWT validation, current user, roles (`CUSTOMER`, `ADMIN`) | 1 |
| `accounts` | Account CRUD, lifecycle, modification history | 1 |
| `transactions` | Transfers, ledger, idempotency, advanced search | 2 |
| `notifications` | Email and SMS (no mobile push), event-driven | 4 |
| `analytics` | Domain events consumed into Elasticsearch | 4 |
| `chatbot` | Secure, read-only access to the user's own data | 7 |

Rule: modules only talk through public APIs or domain events, never through each other's tables.

---

## 2. Domain model (corrected)

### Accounts
- `id` (UUID, DB), `accountId` (business ID, IBAN-like, generated, unique index)
- `type`: `SAVING | CURRENT | CRYPTO`
- `status` state machine: `CREATED → ACTIVE ↔ DISABLED → CLOSED`
- `userId`, `createdAt`, `updatedAt`, `@Version` (optimistic locking)
- "Remove" = soft delete (`CLOSED`), never a hard delete
- History of modifications: **Hibernate Envers** or an `account_history` table (not a list in a column)
- Endpoints: list, get by `accountId`, create, update, close, `PATCH /activate`, `GET /{accountId}/history`

### Transactions
- A **transfer**: sender, receiver, amount (`BigDecimal`), currency, reference, **idempotency key**
- Status: `PENDING | DONE | CANCELLED`
- Two **ledger entries** per transfer (`DEBIT` and `CREDIT`). The balance is derived from the ledger, not stored as a mutable field.
- Advanced search by `transactionId`, `accountId`, `sender`, `receiver` or a combination (JPA Specifications or QueryDSL, on PostgreSQL)

### Auth
**Keycloak is the identity provider.** The backend only validates JWTs and enforces permissions.

- **Keycloak owns:** registration, login, password reset, email verification, MFA and user profiles (name, email; phone and address as attributes for now). No `passwordHash` and no `/login` or `/register` endpoints in the app.
- **Frontend:** Authorization Code + PKCE, public client `ebank-web`, tokens kept in memory.
- **Backend:** stateless OAuth2 Resource Server. It checks signature, expiry, issuer and audience (`ebank-api`).
- **`auth` module:** security config (deny by default), role mapping from `realm_access.roles`, CORS, and a `CurrentUser` API for other modules.
- **User ID:** `userId` is the JWT `sub`. No local `users` table.
- **Roles:** realm roles `CUSTOMER` and `ADMIN`. Data ownership is checked in business code: a customer only sees their own accounts.
- **Sessions:** 5-minute access token, refresh token rotation.
- **Logout:** Keycloak logout, no Redis denylist. Accepted risk: an access token stays valid for up to 5 minutes.
- **MFA:** TOTP required for `ADMIN`, optional for `CUSTOMER`.
- **Deployment:** one `ebank` realm per environment. Keycloak and its database are separate from the app. The realm config is versioned and auto-imported in `dev`; secrets stay out of the repository.
- **Tests:** 401 (no token, expired token, wrong issuer or audience), 403 (wrong role), user A cannot read user B's account, and one end-to-end flow with Keycloak on Testcontainers.
- **Observability:** metrics on 401 and 403, Keycloak events enabled, no tokens or emails in logs.
- **Deferred:** `ebank-security-starter` (extract with the first microservice), BFF, step-up MFA on transfers, Keycloak profile access from `notifications` (phase 4).

### Non-negotiable rules
- Money is `BigDecimal` plus a currency, never `double`
- Two simultaneous debits on one account must be tested
- All writes that must produce an event go through the **transactional outbox**

---

## 3. Stack

| Area | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 4.1.x (Spring Framework 7, Jackson 3), Spring MVC + **virtual threads** |
| Build | Maven (multi-module) |
| Database | PostgreSQL (latest stable major), **Flyway** migrations |
| Cache / misc | Redis (rate limiting, idempotency keys) |
| Identity | Keycloak (container, pinned version), Spring Security OAuth2 Resource Server |
| Mapping | Records for DTOs (no Lombok), **MapStruct** |
| API docs | springdoc-openapi (Boot 4-compatible version) |
| Errors | Global `@RestControllerAdvice`, custom exceptions, `ProblemDetail` (RFC 9457) |
| Profiles | `dev`, `qua`, `prod` |
| Messaging | Outbox first, then Kafka or RabbitMQ (phase 4) |
| Frontend | Angular 21 |
| Analytics | Elasticsearch (separate indices from logs) |
| Observability | Actuator, Micrometer, Prometheus/Grafana, ELK, OpenTelemetry |
| Containers | Docker, docker compose (dev only), k3s |
| CI/CD | Jenkins, SonarQube, DockerHub |
| Tests | Unit tests first (backend + frontend). Integration tests with Testcontainers later. |

> Verify exact versions (Spring Boot 4.1.2, springdoc, Angular 21) on start.spring.io and npm before locking them in.

### Coding conventions
- `info` / `warn` / `error` logs with meaning, no PII or secrets in logs
- Actuator exposed for health, metrics, traces, loggers (secured outside `dev`)
- Beware of `synchronized` blocks pinning carrier threads with virtual threads on Java 21

### DevSecOps mapping

| Need | Tool |
|---|---|
| SBOM | CycloneDX |
| SCA | OWASP Dependency-Check or Trivy |
| SAST | Semgrep or SpotBugs + FindSecBugs, plus SonarQube |
| DAST | OWASP ZAP |
| Image scan | Trivy |

Add them one at a time, not all at once.

---

## 4. Roadmap

| Phase | Goal | Content | Done |
|---|---|---|---|
| **0** | Foundations | Maven multi-module, profiles, docker-compose (Postgres, Redis, Keycloak and its database), Flyway, Actuator, springdoc, global exception handler | ☐ |
| **1** | Core banking | `auth` (Keycloak realm, Resource Server) + `accounts` (CRUD, state machine, history), unit tests | ☐ |
| **2** | Transactions | Ledger, transfers, idempotency, optimistic locking, advanced search | ☐ |
| **3** | Frontend | Angular 21: OIDC login and logout (PKCE), accounts, transactions | ☐ |
| **4** | Events | Outbox, broker, `notifications`, `analytics` → Elasticsearch | ☐ |
| **5** | Observability | Prometheus/Grafana, ELK, OpenTelemetry tracing | ☐ |
| **6** | Delivery | Dockerfiles, k3s manifests or Helm, Jenkins + SonarQube + security scans | ☐ |
| **7** | Chatbot | Secure tool calling on user-scoped data | ☐ |

**Core = phases 0 to 2.** If these are done well, the project is already a success.

### Later: extraction to microservices
Extract `notifications` and `chatbot` first (phase 4 onward), once the broker, tracing and K8s are in place. The modular boundaries from phase 0 make this cheap.

---

## 5. Chatbot security rules (phase 7)

- LLM **tool calling** (Spring AI) with **read-only tools** only
- `userId` always comes from the **JWT**, never from the LLM
- Defend against prompt injection, mask PII in logs and prompts
- Audit log of every tool call
- Rate limit per user

---

## 6. Next step

Scaffold **phase 0**: Maven multi-module layout, `application-{dev,qua,prod}.yml`, `docker-compose.yml` (Postgres, Redis, Mailpit, Keycloak and its database), versioned `ebank` realm export, Flyway baseline, Actuator, springdoc and the global exception handler.