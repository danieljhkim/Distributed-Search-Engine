# Invariants (must always hold)

## Correctness
- [ ] Define pagination consistency rules (e.g., stable ordering / tie-breakers)
- [ ] Define routing determinism rules (same inputs → same shard selection)
- [ ] Define backward compatibility for external contracts (API/proto/schema)

## Performance
- [ ] Define latency budgets (P50/P95/P99) for local + staging
- [ ] Define fan-out limits / concurrency limits

## Safety
- [ ] Read-only mode must never execute destructive commands
- [ ] Any destructive operation must be two-step: plan -> execute
