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
