# Benchmark result artifacts

This directory intentionally contains no published performance numbers. Each run
creates `run-<UTC timestamp>-<target>/` with `metadata.json`, `ingestion.json`,
one k6 summary per workload, the corresponding raw k6 log, and `run.json`.

`ci-smoke` artifacts demonstrate a reproducible public-API contract on twelve
documents. They are not capacity evidence. Capacity claims require the declared
capacity profile, at least five independent runs at every concurrency level,
the complete raw artifacts, a confidence interval (or equivalent repeated-run
variability), and the first concurrency level where the latency/error objective
is breached as the saturation point.
