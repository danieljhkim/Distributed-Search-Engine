# Metrics Convention (Optional)

This document describes the **local metrics setup and conventions** for the dsearch system when running under DevBox.

Metrics are optional but useful for:
- validating performance changes (latency/throughput)
- detecting regressions (error rate, timeouts)
- diagnosing load-related failures

---

## Metrics Technology

dsearch uses **Prometheus-style** metrics.

- Format: Prometheus exposition format
- Transport: HTTP
- Collection: pull-based scraping (Prometheus or compatible)

If you are using Micrometer (Spring Boot), it should expose metrics in Prometheus format.

---

## Local Metrics Endpoints

Keep the ports stable across runs.

### Gateway (Spring Boot)
- Endpoint: `http://localhost:8080/actuator/prometheus`

### Coordinator
- Endpoint: `http://localhost:7001/metrics` (recommended)
  - If coordinator does not expose HTTP metrics yet, document the future port here and keep it stable.

### Query Nodes
- Node 0: `http://localhost:6002/metrics` (recommended)
- Node 1: `http://localhost:6003/metrics` (recommended)

### Index Nodes
- Node 0: `http://localhost:5002/metrics` (recommended)
- Node 1: `http://localhost:5003/metrics` (recommended)

Notes:
- `8080` and `7000` are reserved for the gateway and coordinator gRPC/HTTP control paths.
- Metrics ports are separated to avoid coupling readiness/serving with observability.
- If your implementation differs, update the URLs above, but keep the pattern stable.

---

## Metric Naming Conventions

Prefer conventional Prometheus naming:

- Counters: `*_total`
- Histograms: `*_bucket`, `*_sum`, `*_count`
- Gauges: no suffix

Use a consistent prefix for dsearch:

- `dsearch_gateway_*`
- `dsearch_query_*`
- `dsearch_index_*`
- `dsearch_coordinator_*`

Recommended core metrics:
- Requests: `dsearch_*_requests_total`
- Errors: `dsearch_*_errors_total`
- Latency: `dsearch_*_request_duration_seconds` (histogram)
- Queue depth / thread pool: `dsearch_*_queue_depth` (gauge)
- JVM (if applicable): `jvm_*` (Micrometer default set)

---

## Labels

Keep labels low-cardinality.

Recommended labels:
- `service` (gateway/query/index/coordinator)
- `node_id` (e.g. `query-0`, `index-1`)
- `shard_id` (only if bounded and small)
- `method` (for RPC/HTTP method name)
- `status` (success/error class)

Avoid high-cardinality labels:
- `doc_id`
- raw query strings
- user/session identifiers

---

## Local Scraping (Optional)

If you run Prometheus locally, a minimal scrape config might look like:

```yaml
scrape_configs:
  - job_name: "dsearch"
    static_configs:
      - targets:
          - "localhost:8080" # gateway actuator/prometheus
          - "localhost:7001" # coordinator metrics
          - "localhost:6002" # query-0 metrics
          - "localhost:6003" # query-1 metrics
          - "localhost:5002" # index-0 metrics
          - "localhost:5003" # index-1 metrics
```

For the gateway actuator endpoint, configure `metrics_path: /actuator/prometheus`.

---

## DevBox Interaction

DevBox does not manage metrics infrastructure.

DevBox responsibilities:
- document expected endpoints and ports
- keep metric naming conventions stable
- treat metrics as an **optional signal**

Logs and health checks remain the primary machine-readable signals for correctness.

---

## Summary

Metrics in dsearch are:
- Optional
- Prometheus-compatible
- Pull-based over HTTP
- Designed for low-cardinality, stable endpoints
