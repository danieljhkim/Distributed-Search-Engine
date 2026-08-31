# Benchmark methodology

The `benchmark/` harness tests the public `/api/v1/index` and `/api/v1/search`
contracts, not a direct in-process Lucene path. It separates reproducible CI
smoke evidence from capacity evidence.

## CI smoke evidence

The CI benchmark-harness check runs `make -C benchmark check`. It verifies the
three mode entrypoints, including that the semantic target resolves to
`semantic_search.js` rather than the hybrid script; the query-mix assertions;
the fan-out and structured-error assertions; and shell syntax. CI smoke data is
the 12-document `benchmark/datasets/ci-smoke.jsonl` fixture. It prevents wiring
and semantic-regression mistakes, but does not publish latency or throughput
capacity claims because shared CI hardware is not controlled.

## Capacity evidence

Use the `capacity` profile in `benchmark/config/scenarios.yaml`, generate a
dataset and its accompanying manifest, and run each workload at least five times per
concurrency level. Preserve every generated result directory. A published
summary must include source commit, image digest, configuration hash, embedding
model identity, dataset manifest/hash/count, warmup, duration, concurrency,
hardware, JVM settings, and raw artifact links.

Report median and a confidence interval or repeated-run variability for latency
and throughput. Include the first concurrency where the latency or error
objective is breached as the saturation point. Publish overload and injected
node/coordinator failure outcomes alongside steady-state results; do not remove
negative runs from the evidence set.

## Assertions

Representative requests assert expected document IDs, rank bounds, total-hit
relevance thresholds, and fan-out metadata. Overload tests assert a configured
429/503 response plus a structured error body. Failure tests assert either
`PARTIAL_FAILURE` fan-out metadata or an explicit unavailable error. Ingestion
records individual index acknowledgements and elapsed time so refresh/commit
cost is visible in the raw artifact.
