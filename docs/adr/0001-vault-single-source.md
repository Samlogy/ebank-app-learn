# ADR 0001: Vault as the single source, read by Spring Cloud Vault in the backend and External Secrets Operator for the rest

- **Status:** accepted
- **Date:** 2026-11-14
- **Scope:** configuration and secrets of every environment (`local`, `dev`, `qua`, `prod`)

## Context

Before this decision, settings lived in three places: environment variables in docker compose, a ConfigMap and hand-made Secrets in each k3s namespace, and Helm values. A database password changed in one place and not in another broke a deployment; nobody could say who had read a secret; and the `qua` and `prod` secrets had to be typed into `kubectl create secret` on a laptop.

The workloads do not all have the same capabilities:

- the **backend** is our own Spring Boot application: it can call Vault, authenticate, and renew leases;
- **PostgreSQL, Redis, Keycloak, RabbitMQ and the front end's nginx** are third-party images: they only read environment variables or files.

## Decision

1. **Vault is the only source** of config and secrets, for every environment. One KV v2 mount, `ebank`, one folder per environment (`ebank/<env>/...`), keys named like the environment variables the code already reads. Non-secret config is kept in git (`infra/vault/<env>/*-config.json`) and written to Vault by CI before each deploy, so it is still reviewed. `qua` and `prod` secrets are never in git: a person writes them in Vault.
2. **The backend reads Vault itself, with Spring Cloud Vault** (`spring.config.import: vault://`): Kubernetes auth with a token projected for the `vault` audience, `fail-fast` in `qua` and `prod`. No Kubernetes Secret holds the backend's secrets.
3. **Everything else gets Kubernetes Secrets from External Secrets Operator** (one `SecretStore` per namespace, Kubernetes auth as `ebank-external-secrets`), and **Reloader** restarts those pods when a Secret changes.
4. **One policy per component and environment**: the backend reads `backend/*` (and its own dynamic database credentials), ESO reads only what the third-party images need, CI can only write non-secret config. A `qua` pod cannot read `prod`. Every access is in Vault's audit log.

## Alternatives considered

| Option | Why not |
|---|---|
| **ESO for the backend too** (Vault → Secret → env vars) | The backend's secrets would also exist as a Kubernetes Secret, readable by anyone with `get secrets` in the namespace, and would only change on a pod restart. It also rules out dynamic credentials, whose leases must be renewed by the application. |
| **Vault Agent injector** (sidecar writing files) | One more container per pod, templates to maintain per image, and files the third-party images would still need to be told about. Spring Cloud Vault already does it natively for the backend, and ESO is simpler for the others. |
| **Sealed Secrets or SOPS in git** | Secrets would be versioned (encrypted) in git: rotating one means a commit and a redeploy, there is no access log, and leaked keys decrypt the whole history. |
| **Spring Cloud Config Server** | Another service to run and secure, and it does not solve the third-party images. |
| **Status quo** (Helm values and hand-made Secrets) | The problems listed in the context. |

## Consequences

- **Vault becomes critical.** New backend pods do not start without it (`fail-fast`), so it is monitored (`VaultDown`, `VaultSealed`...) and `prod` runs three servers with transit auto-unseal. Running pods survive an outage: the backend has loaded its values and ESO keeps the Secrets (`deletionPolicy: Retain`). With dynamic database credentials, running backends need Vault at least once an hour.
- **Two ways to apply a change**, depending on the reader: the backend reads Vault at startup, so it needs a restart (CI does it on each deploy); ESO refreshes every 15 minutes and Reloader restarts the other pods. Documented in [infra/vault/README.md](../../infra/vault/README.md).
- **A shared secret is written twice** when two readers need it (the database password in `postgres` and `backend/secrets`; the notifications client secret in `keycloak` and `backend/secrets`). Dynamic database credentials remove the first case.
- **Docker compose follows the same model**: a dev Vault seeded from `infra/vault/local` on each start, and the backend container only gets the Vault connection. Developers see the same paths and keys as in k3s.
- **The old mode stays available** (`configSource: chart`) for a cluster without Vault.
- Adding a third-party component means a Vault path, a line in the ESO policy and an `ExternalSecret`; adding a setting to the backend means a key in Vault and nothing else.
