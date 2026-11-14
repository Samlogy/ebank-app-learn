# Build agent for the EBANK pipeline: JDK 21, Node 24, the Docker CLI (Testcontainers, image
# builds and the dockerized scanners in infra/ci/security/scan.sh), and kubectl, Helm and the Vault
# CLI for deployments.
# The pipeline mounts the host's Docker socket, so the agent needs no Docker daemon of its own.
FROM eclipse-temurin:21.0.13_9-jdk-noble

# glibc build of Node: the base image is Ubuntu, so the Alpine (musl) binary would not run.
COPY --from=node:24.22.2-bookworm-slim /usr/local/bin/node /usr/local/bin/node
COPY --from=node:24.22.2-bookworm-slim /usr/local/lib/node_modules /usr/local/lib/node_modules
COPY --from=docker:28.5.1-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=registry.k8s.io/kubectl:v1.34.1 /bin/kubectl /usr/local/bin/kubectl
COPY --from=alpine/helm:3.19.0 /usr/bin/helm /usr/local/bin/helm
# Writes each environment's non-secret config to Vault before a deploy (infra/vault/apply.sh).
COPY --from=hashicorp/vault:2.2.1 /bin/vault /usr/local/bin/vault

RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && apt-get update && apt-get install -y --no-install-recommends git jq curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
