# VaultLeaseCountHigh

**Meaning.** More than 5000 leases exist in Vault. With dynamic database credentials each backend start creates a lease (and a database user); a normal count is a few per environment.

**Impact.** None yet. Left alone: Vault slows down, and PostgreSQL fills with users.

**Check.**
1. Which path? `vault list sys/leases/lookup/database/creds/` then `vault list sys/leases/lookup/database/creds/ebank-<env>-backend` (admin token).
2. A backend in a crash loop: each start gets a new user (`kubectl -n ebank-<env> get pods`, restarts column).
3. Users in PostgreSQL: `SELECT count(*) FROM pg_roles WHERE rolname LIKE 'v-%';`

**Fix.** Stop the loop first (fix or roll back the backend). Leases end on their own after the TTL (1 h without renewal); to clear them now: `vault lease revoke -prefix database/creds/ebank-<env>-backend`, then restart the backend so it gets a fresh user.

**Escalate.** A count that grows with a healthy backend: a backend developer (leases not reused, see `VaultDatabaseCredentials`).
