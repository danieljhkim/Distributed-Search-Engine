# dsearch Benchmarks

This module contains reproducible benchmarks for the distributed search engine
(dsearch) – inspired by Elasticsearch Rally and Lucene's benchmark module.

## Components

- **datasets/** – sample JSON documents for indexing
- **config/** – common benchmark configuration (env vars, scenarios)
- **k6/** – HTTP load tests against the Gateway (Spring Boot)
- **scripts/** – helper shell scripts to run benchmarks
- **results/** - benchmark results
