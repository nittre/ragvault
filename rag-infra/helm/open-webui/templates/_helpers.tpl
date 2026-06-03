{{- define "open-webui.fullname" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "open-webui.labels" -}}
app.kubernetes.io/name: open-webui
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "open-webui.selectorLabels" -}}
app.kubernetes.io/name: open-webui
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
