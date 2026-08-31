# dsearch public-API benchmark entrypoints. Results are written to a unique
# directory beneath results/; committed files describe the result contract only.
SHELL := /bin/bash

BENCH_DIR := $(CURDIR)
SCRIPTS_DIR := $(BENCH_DIR)/scripts
RESULTS_DIR := $(BENCH_DIR)/results

include config/bench.env.example
export

.PHONY: help env check ingest generate-dataset smoke bm25 semantic hybrid overload failure capacity clean

help:
	@echo "dsearch benchmark suite"
	@echo "  make smoke     Run the small, reproducible public-API smoke workload"
	@echo "  make bm25|semantic|hybrid  Run one search mode with semantic assertions"
	@echo "  make overload|failure       Run negative and partial-failure scenarios"
	@echo "  make capacity  Run the declared capacity workload; use repeated runs"
	@echo "  make check     Verify benchmark target and manifest contracts (no cluster required)"

env:
	@$(SCRIPTS_DIR)/collect_metadata.sh --print

check:
	@$(SCRIPTS_DIR)/test_benchmark_harness.sh

ingest:
	@$(SCRIPTS_DIR)/ingest_dataset.sh "$(DSEARCH_BENCH_DATASET)"

generate-dataset:
	@$(SCRIPTS_DIR)/generate_dataset.sh "$(DSEARCH_GENERATED_DATASET)" "$(DSEARCH_DATASET_DOCUMENTS)"

smoke:
	@$(SCRIPTS_DIR)/run_gateway_benchmark.sh smoke all

bm25 semantic hybrid overload failure:
	@$(SCRIPTS_DIR)/run_gateway_benchmark.sh "$@" single

capacity:
	@$(SCRIPTS_DIR)/run_capacity_benchmark.sh

clean:
	@find "$(RESULTS_DIR)" -mindepth 1 -maxdepth 1 -type d -name 'run-*' -exec rm -rf {} +
