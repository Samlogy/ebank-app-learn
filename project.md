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
| `notifications` | In-app real-time (WebSocket) with email/SMS fallback when offline (no mobile push), event-driven | 4, 8a |
| `analytics` | Domain events consumed into Elasticsearch | 4 |
| `chatbot` | LLM assistant with read-only access to the user's own data, plus handoff to a human advisor: real-time chat, file sharing, audio messages, 1:1 audio/video calls | 7, 8 |

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
- **Roles:** realm roles `CUSTOMER`, `ADVISOR` and `ADMIN`. Data ownership is checked in business code: a customer only sees their own accounts. An advisor only sees conversations waiting in the queue or assigned to them, and has no direct access to customers' banking data.
- **Sessions:** 5-minute access token, refresh token rotation.
- **Logout:** Keycloak logout, no Redis denylist. Accepted risk: an access token stays valid for up to 5 minutes.
- **MFA:** TOTP required for `ADMIN` and `ADVISOR`, optional for `CUSTOMER`.
- **Deployment:** one `ebank` realm per environment. Keycloak and its database are separate from the app. The realm config is versioned and auto-imported in `dev`; secrets stay out of the repository.
- **Tests:** 401 (no token, expired token, wrong issuer or audience), 403 (wrong role), user A cannot read user B's account, and one end-to-end flow with Keycloak on Testcontainers.
- **Observability:** metrics on 401 and 403, Keycloak events enabled, no tokens or emails in logs.
- **Deferred:** `ebank-security-starter` (extract with the first microservice), BFF, step-up MFA on transfers, Keycloak profile access from `notifications` (phase 4).

### Chatbot & advisor handoff (phase 8)
One `chatbot` module. A conversation starts with the LLM assistant. It moves to a human advisor when the customer asks for one, or when the LLM can't handle the request.

- **Participants:** customer ↔ advisor only (no customer-to-customer chat).
- **Conversation state:** `BOT → WAITING_ADVISOR → WITH_ADVISOR → RESOLVED` (the customer can go back to `BOT`). `handoffReason`: `USER_REQUEST | LLM_ESCALATION`.
- **Handoff:** the LLM calls the `escalate_to_advisor` tool, or the customer clicks "talk to an advisor". The conversation joins a queue and is assigned to the first available advisor, or an advisor picks it from the queue. Advisor presence (online or offline) comes from their WebSocket session. If no advisor is online, the customer is told so and the request stays in the queue (advisors get an email).
- **Advisor view:** the full transcript, including the bot part, plus a short LLM summary of the request.
- **What each state allows:** `BOT` = text only (no files or audio sent to the LLM). `WITH_ADVISOR` = text, files, audio messages and 1:1 audio/video calls.
- **Messages:** type `TEXT | FILE | AUDIO | SYSTEM`, a per-conversation sequence number for ordering, a client-generated id for idempotency, delivery and read receipts, soft delete.
- **Attachments:** stored in object storage. Metadata in Postgres. Scan status `PENDING_SCAN → CLEAN | INFECTED`, and a file can only be opened when `CLEAN`.
- **Audio messages:** recorded in the browser (`MediaRecorder`, Opus/WebM, MP4/AAC on Safari). Stored as an `AUDIO` attachment with its duration, using the same pipeline as files.
- **Calls:** `call_session` with state `RINGING → ACTIVE → ENDED`, or `MISSED | REJECTED`. A missed call creates a notification.
- **Tables:** `conversation`, `conversation_participant`, `message`, `attachment`, `call_session`, `notification`, `notification_preference`.
- **Packages:** `assistant` (LLM, tools), `conversation`, `handoff`, `realtime`, `call`, `attachment`. This keeps the human side easy to split off later.

### Notifications (phases 4 and 8a)
- **Sources:** `TransactionCompleted` (through the outbox), `MessageReceived`, `IncomingCall`, `MissedCall`.
- **Delivery:** saved in the `notification` table (read/unread) and pushed live to `/user/queue/notifications`. If the user is offline, fall back to email (or SMS) based on `notification_preference`.
- No mobile push or browser Web Push for now. Accepted risk: a call to a user with the tab closed is missed, and they get a missed-call notification.

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
| Real-time | Spring WebSocket + STOMP, one connection per user for chat, call signaling and notifications. Simple broker in the monolith, then a broker relay (RabbitMQ STOMP or Redis pub/sub) when there are several instances |
| Calls | WebRTC 1:1 P2P: the backend only relays the signaling (offer/answer/ICE). **coturn** (STUN/TURN). An SFU (LiveKit) only if group calls or recording become necessary |
| Object storage | **MinIO** (S3 API), short-lived presigned URLs for upload and download |
| File scanning | **ClamAV**, plus size, MIME and magic-byte checks |
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
| **8a** | In-app notifications | WebSocket/STOMP with JWT auth, `notification` table, live push, email fallback. Only needs phase 4, so it can start early | ☐ |
| **8b** | Advisor handoff + chat | `ADVISOR` role, conversation state machine, queue and assignment, presence, real-time text chat, advisor workspace in front-web | ☐ |
| **8c** | Files & audio messages | MinIO, presigned URLs, ClamAV scan, `MediaRecorder` audio messages | ☐ |
| **8d** | 1:1 calls | WebRTC audio/video, STOMP signaling, coturn, call states, missed-call notifications | ☐ |

**Core = phases 0 to 2.** If these are done well, the project is already a success. Phase 8 is bigger than phases 1 and 2 together. Calls (8d) are the riskiest part, so they come last.

### Later: extraction to microservices
Extract `notifications` and `chatbot` first (phase 4 onward), once the broker, tracing and K8s are in place. The modular boundaries from phase 0 make this cheap. WebSocket connections are stateful: running several instances needs a STOMP broker relay (or sticky sessions), and the ingress and nginx must allow WebSocket upgrades.

---

## 5. Chatbot security rules (phases 7 and 8)

- LLM **tool calling** (Spring AI) with **read-only tools** only. **One exception:** `escalate_to_advisor`, which only changes the conversation state and never touches banking data
- `userId` always comes from the **JWT**, never from the LLM or the client
- Defend against prompt injection, mask PII in logs and prompts. While talking to the bot, only text is allowed (no files or audio sent to the LLM)
- Audit log of every tool call, handoff, call and file access
- Rate limit per user (messages, uploads, calls) with Redis
- WebSocket: JWT checked on STOMP `CONNECT`, token expiry re-checked on the long-lived connection (session closed or re-authenticated after a refresh), and every subscription and message checked for participant rights
- Only participants can read a conversation, download its files or join its call
- Files: size and type limits, magic-byte check, ClamAV scan before access, presigned URLs that expire after a few minutes
- Message size limit. Content is rendered sanitized (Angular, no `innerHTML` bypass)
- TLS in transit and encryption at rest. **No end-to-end encryption**: the bank must keep and audit conversations (compliance)
- Retention policy and GDPR deletion. No message content or file names in logs

### Observability (phase 8)
Active WebSocket sessions, message delivery latency, queue wait time before an advisor takes over, call setup success rate, TURN usage, file scan results.

### Tests (phase 8)
STOMP client integration tests (Testcontainers), handoff state machine unit tests, user A cannot join user B's conversation, Playwright with Chromium fake media flags for calls.

---

## 6. Next step

Scaffold **phase 0**: Maven multi-module layout, `application-{dev,qua,prod}.yml`, `docker-compose.yml` (Postgres, Redis, Mailpit, Keycloak and its database; MinIO, ClamAV and coturn come with phase 8), versioned `ebank` realm export, Flyway baseline, Actuator, springdoc and the global exception handler.