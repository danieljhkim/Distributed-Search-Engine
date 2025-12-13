# Tracing Convention (Optional)

This document describes the **local tracing setup and conventions** for the dsearch system when running under DevBox.

Tracing is optional but recommended for understanding request flow across:
- Gateway
- Query Nodes
- Index Nodes
- Coordinator

---

## Tracing Technology

dsearch uses **OpenTelemetry** for distributed tracing.

- SDK: OpenTelemetry (language-specific per component)
- Context propagation: W3C Trace Context
- Span format: OTLP

---

## Local Exporter & Collector

### Exporter
Each component exports traces using the **OTLP gRPC exporter**.

Typical configuration (example):

```env
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_TRACES_EXPORTER=otlp
OTEL_SERVICE_NAME=<service-name>
```

Service names should be stable and explicit:
- `dsearch-gateway`
- `dsearch-query-node`
- `dsearch-index-node`
- `dsearch-coordinator`

---

### Collector (Local)

For local development, traces are collected by a **local OpenTelemetry Collector**.

Recommended local endpoint:
- **OTLP gRPC:** `localhost:4317`

The collector may forward traces to:
- a local trace backend (Jaeger / Tempo)
- or simply log spans for debugging

The collector itself is considered part of the **local runtime**, not DevBox.

---

## Local Trace Viewer

If a trace UI is enabled, keep the endpoint **stable** across runs.

Recommended options:

### Jaeger (example)
- UI: `http://localhost:16686`
- Purpose: interactive trace exploration

### Grafana Tempo (example)
- UI: `http://localhost:3000`
- Purpose: correlation with metrics/logs

The exact backend is not mandated; stability of the endpoint is preferred.

---

## Conventions

- Tracing is **best-effort** and must not block request handling.
- Tracing failures must not fail the system.
- Sampling should be conservative for local runs.
- Do not assume tracing is enabled in all environments.

---

## DevBox Interaction

DevBox does **not** manage tracing infrastructure.

DevBox responsibilities:
- Document expected trace endpoints
- Keep trace-related configuration discoverable
- Treat traces as an **optional signal**

Agents may use traces for diagnosis if available, but must not depend on them for correctness.

---

## Summary

Tracing in dsearch is:
- Optional
- Local-first
- Non-blocking
- Observational only

Logs and health checks remain the primary machine-readable signals.
