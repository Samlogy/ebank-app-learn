# Observability in k3s

Docker compose has its own stack (`--profile observability`, see the main README). In the cluster:

| Chart | Values | What |
|---|---|---|
| `prometheus-community/kube-prometheus-stack` 93.3.0 | [kube-prometheus-stack-values.yaml](kube-prometheus-stack-values.yaml) | Prometheus operator, Prometheus (35 days, for the 30-day SLOs), Alertmanager (email, SMTP password from a Secret), Grafana (dashboards from labelled ConfigMaps, Loki data source) |
| `grafana/loki` 7.6.0 | [loki-values.yaml](loki-values.yaml) | Loki as a single binary on a volume, 14 days of logs |
| `grafana/alloy` 1.16.0 | [alloy-values.yaml](alloy-values.yaml) | Ships the pod logs of `ebank-dev`, `ebank-qua` and `ebank-prod` to Loki, through the Kubernetes API: no root, no host path |

## Install

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts

kubectl create namespace monitoring
# Never in git: Grafana's admin and the SMTP password Alertmanager sends with.
kubectl -n monitoring create secret generic grafana-admin --from-literal=admin-user=admin --from-literal=admin-password='...'
kubectl -n monitoring create secret generic alertmanager-smtp --from-literal=password='...'

helm upgrade --install monitoring prometheus-community/kube-prometheus-stack --version 93.3.0 -n monitoring \
  -f infra/observability/k8s/kube-prometheus-stack-values.yaml
helm upgrade --install loki grafana/loki --version 7.6.0 -n monitoring -f infra/observability/k8s/loki-values.yaml
helm upgrade --install alloy grafana/alloy --version 1.16.0 -n monitoring -f infra/observability/k8s/alloy-values.yaml

# Then the targets:
#   Vault:  --set serverTelemetry.serviceMonitor.enabled=true on its install (infra/vault/README.md)
#   EBANK:  monitoring.enabled: true in values-<env>.yaml (ServiceMonitors, PrometheusRules, dashboard)
```

Set the SMTP server, sender and recipient in `kube-prometheus-stack-values.yaml` first (`smtp.example` placeholders). The namespace must be `monitoring`: the EBANK NetworkPolicies only let it reach the actuator port (8081) and RabbitMQ's metrics (15692).

## What comes from where

- **Rules**: the same files as compose, [prometheus/rules](../prometheus/rules), turned into PrometheusRules by the EBANK chart (through `infra/helm/ebank/files/`). The chart relabels its targets with `service="ebank-backend"` and `job="ebank-backend"`, like compose's static scrape configs, so the rules are identical. `monitoring.vaultRules` keeps the Vault and External Secrets rules in one release per cluster.
- **Routing**: Alertmanager's config in the values mirrors [alertmanager.yml](../alertmanager/alertmanager.yml), with a real SMTP server, the operator's `Watchdog` sent nowhere (wire it to a dead man's switch), and inhibitions scoped by namespace.
- **Dashboards**: [grafana/dashboards](../grafana/dashboards), as a ConfigMap labelled `grafana_dashboard` that Grafana's sidecar loads. Its data source UID is `prometheus`, which the stack's Grafana uses too.
- **Logs**: ELK stays in compose (Filebeat needs root and the Docker socket, acceptable on a laptop only). In the cluster Loki is lighter: it indexes a few labels (`namespace`, `app`, `pod`, `container`, `level`), keeps the trace ID as structured metadata, and leaves the rest in the line.

Checked without a cluster: each chart renders with these values, the manifests pass kubeconform (CRDs included), the rendered Alertmanager config passes `amtool check-config`, Loki's `-verify-config` and `alloy validate` accept theirs. Not yet run on a real cluster.
