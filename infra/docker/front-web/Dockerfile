# Build context: front-web/
# nginx.conf comes from the named build context "docker-config" (this folder).
FROM node:24.22.2-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Runs as a non-root user and listens on 8080.
FROM nginxinc/nginx-unprivileged:1.30.6-alpine
COPY --from=docker-config nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/ebank-web/browser/ /usr/share/nginx/html/
EXPOSE 8080
