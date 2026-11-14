# EbankAuthRejectionSpike

**Meaning.** More than 5 rejections per second (401 or 403, label `status`) over 5 minutes.

**Impact.** Either users cannot use the app (configuration problem) or someone is probing the API.

**Check.**
1. 401 for everyone at once, often right after a change:
   - The token issuer does not match `KEYCLOAK_ISSUER_URI` (a new host name), or the audience `ebank-api` is missing.
   - Keycloak rotated its realm keys and the backend cannot reach `KEYCLOAK_JWK_SET_URI`.
   Decode a fresh token (`iss`, `aud`) and compare with the backend config.
2. 401 from a few sources only: look at the client IPs in the ingress (Traefik) logs. Many different tokens or none at all is probing.
3. 403: a role is missing (for example `ADMIN` for deposits) or a frontend calls an admin endpoint by mistake.

**Fix.** Configuration: correct the issuer or JWKS URI and redeploy. Probing: block the source at the ingress, and check Keycloak's login events (brute-force detection is on).

**Escalate.** Probing that continues, or any sign that a valid token was stolen: treat it as a security incident.
