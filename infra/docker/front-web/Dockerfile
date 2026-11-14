# Build context: front-web/
# The nginx config comes from the named build context "docker-config" (this folder).
FROM node:24.22.2-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Runs as a non-root user and listens on 8080.
FROM nginxinc/nginx-unprivileged:1.30.6-alpine
COPY --from=docker-config default.conf /etc/nginx/conf.d/default.conf
COPY --from=docker-config templates/ /etc/nginx/templates/
COPY --from=docker-config --chmod=755 15-ebank-output-dir.sh /docker-entrypoint.d/
COPY --from=build /workspace/dist/ebank-web/browser/ /usr/share/nginx/html/
# Already the base image's user; stated here so scanners and readers don't have to know that.
USER 101
EXPOSE 8080
# The entrypoint renders /etc/nginx/templates/*.template into /tmp/nginx on every start.
# They give /config.json and the CSP origins. The defaults match local development; compose and
# the Helm chart set them per environment.
ENV NGINX_ENVSUBST_OUTPUT_DIR=/tmp/nginx \
    NGINX_ENVSUBST_FILTER=^EBANK_ \
    EBANK_API_ORIGIN=http://localhost:8080 \
    EBANK_WS_ORIGIN=ws://localhost:8080 \
    EBANK_KEYCLOAK_ORIGIN=http://localhost:8180 \
    EBANK_KEYCLOAK_REALM=ebank \
    EBANK_KEYCLOAK_CLIENT_ID=ebank-web
