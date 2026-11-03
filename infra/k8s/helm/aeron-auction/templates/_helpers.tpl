{{- define "aeron-auction.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "aeron-auction.fullname" -}}
{{- if contains .Chart.Name .Release.Name }}
{{- .Release.Name | trunc 50 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 50 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "aeron-auction.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "aeron-auction.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "aeron-auction.cluster.name" -}}
{{- printf "%s-cluster" (include "aeron-auction.fullname" .) }}
{{- end }}

{{- define "aeron-auction.cluster.headless" -}}
{{- printf "%s-cluster-headless" (include "aeron-auction.fullname" .) }}
{{- end }}

{{- define "aeron-auction.client.name" -}}
{{- printf "%s-client" (include "aeron-auction.fullname" .) }}
{{- end }}

{{- define "aeron-auction.cluster.selectorLabels" -}}
app.kubernetes.io/name: {{ include "aeron-auction.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: cluster
{{- end }}

{{- define "aeron-auction.client.selectorLabels" -}}
app.kubernetes.io/name: {{ include "aeron-auction.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: client
{{- end }}

{{/* DNS suffix shared by all cluster members: <headless>.<ns>.svc.<domain> */}}
{{- define "aeron-auction.cluster.domain" -}}
{{- printf "%s.%s.svc.%s" (include "aeron-auction.cluster.headless" .) .Release.Namespace .Values.clusterDomain }}
{{- end }}

{{/* id,ingress,consensus,log,catchup,archive|... */}}
{{- define "aeron-auction.cluster.members" -}}
{{- $name := include "aeron-auction.cluster.name" . }}
{{- $domain := include "aeron-auction.cluster.domain" . }}
{{- $p := .Values.cluster.ports }}
{{- $members := list }}
{{- range $i := until (int .Values.cluster.replicas) }}
{{- $h := printf "%s-%d.%s" $name $i $domain }}
{{- $members = append $members (printf "%d,%s:%v,%s:%v,%s:%v,%s:%v,%s:%v" $i $h $p.ingress $h $p.consensus $h $p.log $h $p.catchup $h $p.archive) }}
{{- end }}
{{- join "|" $members }}
{{- end }}

{{/* id=host:port,... for the given port */}}
{{- define "aeron-auction.cluster.endpoints" -}}
{{- $ctx := index . 0 }}
{{- $port := index . 1 }}
{{- $name := include "aeron-auction.cluster.name" $ctx }}
{{- $domain := include "aeron-auction.cluster.domain" $ctx }}
{{- $eps := list }}
{{- range $i := until (int $ctx.Values.cluster.replicas) }}
{{- $eps = append $eps (printf "%d=%s-%d.%s:%v" $i $name $i $domain $port) }}
{{- end }}
{{- join "," $eps }}
{{- end }}

{{- define "aeron-auction.antiAffinity" -}}
{{- $mode := index . 0 }}
{{- $labels := index . 1 }}
podAntiAffinity:
{{- if eq $mode "hard" }}
  requiredDuringSchedulingIgnoredDuringExecution:
    - topologyKey: kubernetes.io/hostname
      labelSelector:
        matchLabels:
          {{- $labels | nindent 10 }}
{{- else }}
  preferredDuringSchedulingIgnoredDuringExecution:
    - weight: 100
      podAffinityTerm:
        topologyKey: kubernetes.io/hostname
        labelSelector:
          matchLabels:
            {{- $labels | nindent 12 }}
{{- end }}
{{- end }}
