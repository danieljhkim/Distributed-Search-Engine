# DevBox Makefile
# Run from repo root with: make -C .box <target>
# Or from .box/ with: make <target>

SHELL := /bin/sh
BOX_DIR := .
SCRIPTS := $(BOX_DIR)/scripts
ARGS ?=

.DEFAULT_GOAL := help

.PHONY: help doctor up down health smoke test logs reset status \
        cluster-status metrics api-search api-index \
        box-doctor box-up box-down box-health box-smoke box-test box-logs box-reset

help:
	@echo "DevBox targets:"
	@echo "  make -C .box doctor            Validate environment + contract"
	@echo "  make -C .box up                Start local runtime"
	@echo "  make -C .box down              Stop local runtime"
	@echo "  make -C .box health            Health check (alias: smoke --health)"
	@echo "  make -C .box smoke ARGS=...    Run smoke checks"
	@echo "  make -C .box test  ARGS=...    Run tests"
	@echo "  make -C .box logs  ARGS=...    Inspect logs"
	@echo "  make -C .box reset             Clear ephemeral state"
	@echo ""
	@echo "API targets:"
	@echo "  make -C .box cluster-status    Get cluster health status"
	@echo "  make -C .box metrics           Get Prometheus metrics"
	@echo "  make -C .box api-search        Execute search (pass JSON via ARGS)"
	@echo "  make -C .box api-index         Index document (pass JSON via ARGS)"
	@echo ""
	@echo "Examples:"
	@echo "  make -C .box api-search ARGS='{\"query\":\"test\",\"partitionId\":\"default\"}'"
	@echo "  make -C .box api-index ARGS='{\"partitionId\":\"default\",\"fields\":{\"title\":\"Test\"}}'"

doctor:
	@$(SCRIPTS)/doctor.sh $(ARGS)

up:
	@$(SCRIPTS)/up.sh $(ARGS)

down:
	@$(SCRIPTS)/down.sh $(ARGS)

health:
	@$(SCRIPTS)/smoke.sh --health $(ARGS)

smoke:
	@$(SCRIPTS)/smoke.sh $(ARGS)

test:
	@$(SCRIPTS)/test.sh $(ARGS)

logs:
	@$(SCRIPTS)/logs.sh $(ARGS)

reset:
	@$(SCRIPTS)/reset.sh $(ARGS)

# Namespaced aliases
box-doctor: doctor
box-up: up
box-down: down
box-health: health
box-smoke: smoke
box-test: test
box-logs: logs
box-reset: reset

status:
	@echo "DevBox status:"
	@echo "  Repo root : $$(cd .. && pwd)"
	@echo "  Box dir   : $$(pwd)"
	@echo "  Scripts   : $(SCRIPTS)"
	@echo "  Logs      : $$(cd .. && pwd)/logs"
	@echo "  Reports   : $(BOX_DIR)/reports"
	@echo "  State     : $(BOX_DIR)/state"

# API targets
cluster-status:
	@$(SCRIPTS)/api-cluster-status.sh

metrics:
	@$(SCRIPTS)/api-metrics.sh

api-search:
	@$(SCRIPTS)/api-search.sh '$(ARGS)'

api-index:
	@$(SCRIPTS)/api-index.sh '$(ARGS)'
