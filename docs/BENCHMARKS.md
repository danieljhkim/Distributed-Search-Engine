# Benchmarks

The `./benchmark` module contains load tests using **k6** and **ghz**.  
Benchmark results are logged automatically to `./benchmark/results/`.

---

### Gateway HTTP Benchmarks (k6)

Run BM25-only benchmark:

```bash
cd benchmark
make bm25
```

Hybrid (BM25 + Semantic):

```bash
make hybrid
```

Semantic-only:

```bash
make semantic
```

### QueryNode gRPC Benchmarks (ghz)

BM25:

```bash
make grpc-bm25
```

HYBRID:

```bash
make grpc-hybrid
```
