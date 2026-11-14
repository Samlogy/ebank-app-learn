# External Secrets Operator in one environment's namespace: what the third-party images
# (PostgreSQL, Redis, Keycloak) and the front end's nginx need, as Kubernetes Secrets.
# Not the backend's secrets: the backend reads those itself.
path "ebank/data/__ENV__/postgres"         { capabilities = ["read"] }
path "ebank/data/__ENV__/redis"            { capabilities = ["read"] }
path "ebank/data/__ENV__/keycloak"         { capabilities = ["read"] }
path "ebank/data/__ENV__/keycloak/config"  { capabilities = ["read"] }
path "ebank/data/__ENV__/front-web/config" { capabilities = ["read"] }
path "ebank/data/__ENV__/rabbitmq"         { capabilities = ["read"] }
