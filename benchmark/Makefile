# ===============================================
# dsearch Benchmark Makefile
# ===============================================

SHELL := /bin/bash

BENCH_DIR := $(shell pwd)
SCRIPTS_DIR := $(BENCH_DIR)/scripts
K6_DIR := $(BENCH_DIR)/k6
GHZ_DIR := $(BENCH_DIR)/ghz
DATA_DIR := $(BENCH_DIR)/datasets
RESULTS_DIR := $(BENCH_DIR)/results

# Load environment variables from config
include config/bench.env.example
export

# ===============================================
# General Helpers
# ===============================================

.PHONY: help
help:
	@echo "============================================================="
	@echo " dsearch Benchmark Suite - Available Commands"
	@echo "============================================================="
	@echo ""
	@echo " HTTP Gateway Benchmarks (k6):"
	@echo "   make bm25           - Run BM25-only Gateway load test"
	@echo "   make hybrid         - Run HYBRID (BM25 + embedding) load test"
	@echo "   make semantic       - Run SEMANTIC-only load test"
	@echo ""
	@echo " gRPC QueryNode Benchmarks (ghz):"
	@echo "   make grpc-bm25      - Run QueryNode BM25-only benchmark"
	@echo "   make grpc-hybrid    - Run QueryNode HYBRID benchmark"
	@echo ""
	@echo " Data / Utility:"
	@echo "   make ingest         - Import sample dataset into dsearch"
	@echo "   make env            - Print benchmark environment variables"
	@echo "   make es-bm25        - Compare BM25 latency vs Elasticsearch"
	@echo ""
	@echo " Cleanup:"
	@echo "   make clean          - Remove temp benchmark files"
	@echo ""

# ===============================================
# Environment
# ===============================================

.PHONY: env
env:
	@echo "Current Benchmark Environment:"
	@echo "  DSEARCH_GATEWAY_BASE_URL=$(DSEARCH_GATEWAY_BASE_URL)"
	@echo "  DSEARCH_GATEWAY_SEARCH_PATH=$(DSEARCH_GATEWAY_SEARCH_PATH)"
	@echo "  DSEARCH_QUERYNODE_ADDR=$(DSEARCH_QUERYNODE_ADDR)"
	@echo "  DSEARCH_BENCH_VUS=$(DSEARCH_BENCH_VUS)"
	@echo "  DSEARCH_BENCH_DURATION=$(DSEARCH_BENCH_DURATION)"
	@echo "  DSEARCH_BENCH_QUERY=$(DSEARCH_BENCH_QUERY)"
	@echo "  DSEARCH_BENCH_PAGE=$(DSEARCH_BENCH_PAGE)"
	@echo "  DSEARCH_BENCH_PAGE_SIZE=$(DSEARCH_BENCH_PAGE_SIZE)"
	@echo "  DSEARCH_BENCH_SEARCH_TYPE=$(DSEARCH_BENCH_SEARCH_TYPE)"

# ===============================================
# Dataset Ingestion
# ===============================================

.PHONY: ingest
ingest:
	@echo "Ingesting sample dataset into dsearch..."
	@bash $(SCRIPTS_DIR)/import_sample_data.sh $(DATA_DIR)/small.jsonl

# ===============================================
# k6 HTTP Benchmarks (Gateway)
# ===============================================

.PHONY: bm25
bm25:
	@mkdir -p $(RESULTS_DIR)
	@echo "Running Gateway BM25 benchmark..."
	@DSEARCH_BENCH_SEARCH_TYPE=BM25 \
		k6 run $(K6_DIR)/bm25_search.js > $(RESULTS_DIR)/bm25.log 2>&1

.PHONY: hybrid
hybrid:
	@mkdir -p $(RESULTS_DIR)
	@echo "Running Gateway HYBRID benchmark..."
	@DSEARCH_BENCH_SEARCH_TYPE=HYBRID \
		k6 run $(K6_DIR)/hybrid_search.js > $(RESULTS_DIR)/hybrid.log 2>&1

.PHONY: semantic
semantic:
	@mkdir -p $(RESULTS_DIR)
	@echo "Running Gateway SEMANTIC benchmark..."
	@DSEARCH_BENCH_SEARCH_TYPE=SEMANTIC \
		k6 run $(K6_DIR)/hybrid_search.js > $(RESULTS_DIR)/semantic.log 2>&1

# ===============================================
# gRPC Benchmarks (QueryNode)
# ===============================================

.PHONY: grpc-bm25
grpc-bm25:
	@mkdir -p $(RESULTS_DIR)
	@echo "Running QueryNode BM25 gRPC benchmark..."
	@bash $(SCRIPTS_DIR)/run_ghz_querynode.sh BM25 > $(RESULTS_DIR)/grpc-bm25.log 2>&1

.PHONY: grpc-hybrid
grpc-hybrid:
	@mkdir -p $(RESULTS_DIR)
	@echo "Running QueryNode HYBRID gRPC benchmark..."
	@bash $(SCRIPTS_DIR)/run_ghz_querynode.sh HYBRID > $(RESULTS_DIR)/grpc-hybrid.log 2>&1

# ===============================================
# Elasticsearch benchmark comparison (optional)
# ===============================================

.PHONY: es-bm25
es-bm25:
	@mkdir -p $(RESULTS_DIR)
	@echo "Running Elasticsearch BM25 benchmark..."
	@DSEARCH_GATEWAY_BASE_URL=http://localhost:9200 \
		DSEARCH_GATEWAY_SEARCH_PATH=/docs/_search \
		DSEARCH_BENCH_SEARCH_TYPE=BM25 \
		k6 run $(K6_DIR)/bm25_search.js > $(RESULTS_DIR)/es-bm25.log 2>&1

# ===============================================
# Cleanup
# ===============================================

.PHONY: clean
clean:
	@echo "Cleaning benchmark temp files..."
	@rm -f *.tmp
	@rm -f *.log
	@echo "Done."