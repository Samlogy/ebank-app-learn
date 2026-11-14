#!/bin/sh
# Runs before 20-envsubst-on-templates.sh, which needs its output directory to exist. /tmp is the only
# writable place when the root filesystem is read-only (k3s, or docker run --read-only).
set -eu
mkdir -p "${NGINX_ENVSUBST_OUTPUT_DIR:-/tmp/nginx}"
