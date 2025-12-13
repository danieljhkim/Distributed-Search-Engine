# DevBox Makefile
# Run from repo root with: make -C .box <target>
# Or from .box/ with: make <target>

SHELL := /bin/sh
BOX_DIR := .
SCRIPTS := $(BOX_DIR)/scripts
ARGS ?=

.DEFAULT_GOAL := help

.PHONY: help doctor up down health smoke test logs reset status \
        box-doctor box-up box-down box-health box-smoke box-test box-logs box-reset

help:
	@echo "DevBox targets:"
	@echo "  make -C .box doctor            Validate environment + contract"
	@echo "  make -C .box up                Start local runtime"
	@echo "  make -C .box down              Stop local runtime"
	@echo "  make -C .box health            Health check (alias: smoke --health)"
	@echo "  make -C .box smoke ARGS=...     Run smoke checks"
	@echo "  make -C .box test  ARGS=...     Run tests"
	@echo "  make -C .box logs  ARGS=...     Inspect logs"
	@echo "  make -C .box reset             Clear ephemeral state"

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
