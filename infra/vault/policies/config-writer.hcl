# CI: writes the non-secret config of one environment from git (infra/vault/__ENV__/*-config.json).
# It can neither read nor write any secret.
path "ebank/data/__ENV__/backend/config"   { capabilities = ["create", "update"] }
path "ebank/data/__ENV__/front-web/config" { capabilities = ["create", "update"] }
path "ebank/data/__ENV__/keycloak/config"  { capabilities = ["create", "update"] }
