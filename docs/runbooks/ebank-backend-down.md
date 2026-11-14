# EbankBackendDown

**Meaning.** No backend target has answered a Prometheus scrape of `/actuator/prometheus` for 2 minutes.

**Impact.** The whole API is unavailable: no login to the app's data, no transfers, no chat. While this alert fires, Alertmanager holds back the backend's warning alerts, which are consequences.

**Check.**
1. Is it running? `docker compose ps backend`, or `kubectl -n ebank-<env> get pods -l app.kubernetes.io/name=backend`.
2. Why did it stop? Read the last logs (see [commands](README.md#commands-used-in-the-pages)). Common causes:
   - `APPLICATION FAILED TO START` with a missing property: a required environment variable or secret is missing outside `dev`.
   - Flyway `Validate failed`: a migration was changed after it ran.
   - `Connection refused` to PostgreSQL, Redis or Keycloak's JWKS endpoint.
   - `OOMKilled` in `kubectl describe pod`: see [ebank-heap-almost-full.md](ebank-heap-almost-full.md).
3. Is only the scrape failing? `curl -s localhost:8081/actuator/health` from inside the pod (8080 in `dev`). If the app is healthy, check the scrape target and the NetworkPolicy that lets the `monitoring` namespace reach port 8081.

**Fix.** Fix the cause (missing secret, dependency down, bad migration), then restart. For a bad release: `helm -n ebank-<env> rollback ebank`.

**Escalate.** If PostgreSQL itself is down or its volume is full, call whoever owns the database: data must not be touched by hand.
