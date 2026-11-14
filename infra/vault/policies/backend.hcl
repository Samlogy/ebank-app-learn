# The backend of one environment reads its own config and secrets, nothing else.
# __ENV__ is replaced by apply.sh.
path "ebank/data/__ENV__/backend/*" {
  capabilities = ["read"]
}

# Dynamic PostgreSQL credentials (database/README.md), once enable-dynamic-db.sh has run:
# a new database user per lease, renewed while the backend runs, revoked when it stops.
path "database/creds/ebank-__ENV__-backend" {
  capabilities = ["read"]
}
# Revoking its own leases on shutdown (lease-endpoints LeasesRevokedByPrefix in application.yml):
# revoke-prefix needs sudo, so it is limited to this role's leases. Renewal is in the default policy.
path "sys/leases/revoke-prefix/database/creds/ebank-__ENV__-backend/*" {
  capabilities = ["update", "sudo"]
}
