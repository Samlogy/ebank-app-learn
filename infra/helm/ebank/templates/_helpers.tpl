{{/*
Common labels. Call with (dict "root" $ "name" "<component>").
Selectors only use app.kubernetes.io/name, which the NetworkPolicies match on.
*/}}
{{- define "ebank.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: ebank
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" }}
{{- end }}

{{- define "ebank.selector" -}}
app.kubernetes.io/name: {{ . }}
{{- end }}

{{/* Image of an EBANK component. Call with (dict "root" $ "image" "ebank-backend"). */}}
{{- define "ebank.image" -}}
{{ .root.Values.image.registry }}/{{ .image }}:{{ .root.Values.image.tag | default .root.Chart.AppVersion }}
{{- end }}

{{/* https behind TLS, http otherwise. */}}
{{- define "ebank.scheme" -}}
{{ ternary "https" "http" .Values.ingress.tls.enabled }}
{{- end }}

{{/*
Pod security settings shared by every workload: non-root, RuntimeDefault seccomp.
No Docker-link variables: the "redis" Service would set REDIS_PORT=tcp://..., which Spring reads as its port.
Call with (dict "user" 10001 "group" 10001 "fsGroup" 0).
*/}}
{{- define "ebank.podSecurity" -}}
enableServiceLinks: false
securityContext:
  runAsNonRoot: true
  runAsUser: {{ .user }}
  {{- with .group }}
  runAsGroup: {{ . }}
  {{- end }}
  {{- with .fsGroup }}
  fsGroup: {{ . }}
  {{- end }}
  seccompProfile: { type: RuntimeDefault }
{{- end }}

{{/* Container security settings. Call with (dict "readOnly" true). */}}
{{- define "ebank.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  {{- if .readOnly }}
  readOnlyRootFilesystem: true
  {{- end }}
  capabilities: { drop: ["ALL"] }
{{- end }}

{{/* Fails the render when secrets.create is on and a required key is missing. */}}
{{- define "ebank.requireKeys" -}}
{{- $values := index .root.Values.secrets .section -}}
{{- range .keys }}
{{- if not (hasKey $values .) }}
{{- fail (printf "secrets.%s.%s is required when secrets.create is true" $.section .) }}
{{- end }}
{{- end }}
{{- end }}

{{/* True when Vault is the source of config and secrets. Fails on an unknown value or a missing vault.env. */}}
{{- define "ebank.fromVault" -}}
{{- if eq .Values.configSource "vault" -}}
{{- if not .Values.vault.env }}{{ fail "vault.env is required when configSource is vault" }}{{ end -}}
{{- if .Values.secrets.create }}{{ fail "secrets.create must be false when configSource is vault: the Secrets come from Vault" }}{{ end -}}
true
{{- else if ne .Values.configSource "chart" -}}
{{- fail (printf "configSource must be chart or vault, not %q" .Values.configSource) -}}
{{- end -}}
{{- end }}

{{/* The Secret holding REDIS_PASSWORD: shared with the backend in chart mode, its own in vault mode. */}}
{{- define "ebank.redisSecret" -}}
{{ ternary "ebank-redis" "ebank-backend-secrets" (eq (include "ebank.fromVault" .) "true") }}
{{- end }}

{{/* Restart this workload when one of its Secrets changes (ESO updates them in place). */}}
{{- define "ebank.reloader" -}}
{{- if eq (include "ebank.fromVault" .) "true" }}
reloader.stakater.com/auto: "true"
{{- end }}
{{- end }}
