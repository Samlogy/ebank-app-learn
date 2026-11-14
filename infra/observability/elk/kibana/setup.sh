#!/bin/sh
# One-shot: creates the Kibana data views for logs and analytics once Kibana is up. Safe to re-run.
# Kibana reports "available" slightly before saved objects accept writes, so each call is retried.
set -eu
KIBANA=${KIBANA:-http://kibana:5601}
until curl -fs "$KIBANA/api/status" | grep -q '"level":"available"'; do sleep 5; done
for view in "ebank-logs:ebank-logs-*:@timestamp" "ebank-transactions:ebank-transactions:occurredAt"; do
  id=${view%%:*}; rest=${view#*:}; pattern=${rest%%:*}; field=${rest#*:}
  attempt=1
  until curl -fs -X POST "$KIBANA/api/data_views/data_view" -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
      -d "{\"override\": true, \"data_view\": {\"id\": \"$id\", \"title\": \"$pattern\", \"name\": \"$id\", \"timeFieldName\": \"$field\"}}" >/dev/null; do
    [ "$attempt" -ge 12 ] && { echo "could not create data view $id" >&2; exit 1; }
    attempt=$((attempt + 1)); sleep 5
  done
  echo "data view $id ($pattern)"
done
