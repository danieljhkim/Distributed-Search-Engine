# Protobuf wire contracts

`ProtoWireContractTest` checks the descriptor set emitted by `protoc`, rather
than generated Java line coverage. It protects the public gRPC services and
the fields and enum values used by cluster, index, and query nodes.

The checked baseline is
`src/test/resources/proto-contract-baseline.txt`. Existing entries are
required to remain present with the same field numbers, names, types, labels,
RPC signatures, enum numbers, and reserved ranges. Additive schema evolution
is allowed: new files, messages, fields, enum values, services, RPCs, and
reservations do not need a baseline entry.

To intentionally make a breaking contract change, first decide how rolling
upgrades will remain safe. Then update the `.proto` declaration and regenerate
the descriptor with:

```bash
mvn -B -ntp -pl dk.proto clean test
```

Review the descriptor and update the baseline in the same change. The baseline
format is pipe-delimited and is generated from the descriptor's public names,
numbers, labels, types, and signatures; keep entries sorted with their
corresponding contract. Run the module test, Spotless, and the full reactor
before submitting the change. A baseline update without the corresponding
contract review is not an approval of the wire change.
