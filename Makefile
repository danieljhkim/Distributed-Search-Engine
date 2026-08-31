# ============================
# Dsearch - Makefile
# ============================

SHELL := /bin/bash

# Default number of nodes for multi-cluster mode
N_INDEX_NODES ?= 2
N_QUERY_NODES ?= 2

.PHONY: help build clean run run-multi stop logs reset wipe-data e2e resilience

help:
	@echo ""
	@echo "Available commands:"
	@echo "  make build        - Build all modules (mvn clean package)"
	@echo "  make run          - Start single-node cluster"
	@echo "  make run-multi    - Start multi-node cluster"
	@echo "  make stop         - Kill all running cluster processes"
	@echo "  make logs         - Tail all logs"
	@echo "  make reset        - Clean targets + wipe logs + wipe data"
	@echo "  make clean        - Remove Maven target directories"
	@echo "  make e2e          - Docker cluster end-to-end gate"
	@echo "  make resilience   - Docker cluster resilience gate (see docs/OPERABILITY.md)"
	@echo ""

# ============================
# Build & Clean
# ============================

build:
	rm -rf logs/*
	mvn clean package -DskipTests

clean:
	mvn clean

# ============================
# Run / Stop Cluster
# ============================

run:
	./scripts/run_cluster.sh

run-multi:
	N_INDEX_NODES=$(N_INDEX_NODES) \
	N_QUERY_NODES=$(N_QUERY_NODES) \
	./scripts/run_cluster_multi.sh

stop:
	./scripts/kill_cluster.sh

restart: stop run

# ============================
# Logs & Data Management
# ============================

logs:
	@echo "Tailing logs... Ctrl + C to exit."
	tail -f logs/*

wipe-data:
	rm -rf data/*
	@echo "Data directory wiped."

reset: clean stop wipe-data
	rm -rf logs/*
	@echo "All targets, logs, and data wiped."

# ============================
# Convenience
# ============================


e2e:
	DSEARCH_E2E_DIAGNOSTICS=$(CURDIR)/target/docker-e2e-diagnostics \
	./scripts/docker-cluster-e2e.sh

resilience:
	DSEARCH_RESILIENCE_DIAGNOSTICS=$(CURDIR)/target/docker-resilience-diagnostics \
	./scripts/docker-cluster-resilience.sh

format:
	mvn spotless:apply

lint:
	mvn spotless:check
