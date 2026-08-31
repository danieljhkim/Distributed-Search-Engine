# dsearch benchmarks

The benchmark harness measures the public Gateway HTTP API. It is deliberately
split into a tiny reproducible smoke profile and a separately generated capacity
profile; neither profile turns a successful HTTP status into a relevance claim.

## Quick start

Start the target topology, then run the contract check and smoke workload:

```bash
cd benchmark
make check
make smoke
```

`make smoke` ingests `datasets/ci-smoke.jsonl`, warms the service, then runs
distinct BM25, semantic, and hybrid entrypoints. Each request checks expected
document identity, a rank bound, total-hit threshold, and successful fan-out
metadata. `make semantic` invokes `k6/semantic_search.js` directly; it cannot
silently use the hybrid workload.

## Workload catalogue

- `make ingest` records dataset-scaled ingestion and per-document acknowledgements.
- `make bm25`, `make semantic`, and `make hybrid` use the representative query mix.
- `make overload` expects explicitly configured shedding statuses (default 429/503)
  and a structured error response.
- `make failure` is run only after an operator injects a node or coordinator fault;
  it requires partial fan-out metadata or an explicit unavailable error.
- `make capacity` runs the capacity scenario catalogue. Generate the data first with
  `make generate-dataset DSEARCH_DATASET_DOCUMENTS=100000`, then set
  `DSEARCH_BENCH_DATASET=datasets/generated/capacity.jsonl` and execute repeated runs.

The scenario catalogue in `config/scenarios.yaml` names refresh/commit cost,
representative mixes, steady-state measurements, concurrency saturation,
overload shedding, and injected-failure experiments. Fault injection is an
operator action because the harness must never terminate a shared environment.

## Reproducibility and reporting

Every run writes a separate artifact directory under `results/` and records the
source commit, image digest value, configuration hash, embedding model identity,
dataset path/hash/count, warmup, duration, concurrency, hardware, JVM settings,
and raw result files. Pass `DSEARCH_IMAGE_DIGESTS` and `DSEARCH_EMBEDDING_MODEL`
when the runtime cannot discover them.

Do not commit result directories or treat CI smoke output as a capacity claim.
For capacity reporting, retain raw artifacts, report five or more runs per
concurrency with confidence intervals or variability, record negative/overload
outcomes, and identify the first failed latency/error objective as saturation.
