

# Changelog — dsearch

All notable changes to the **dsearch** distributed search engine will be documented in this file.

This project follows a **Keep a Changelog–inspired** format and adheres loosely to
**Semantic Versioning**, adapted for an evolving infrastructure project.

---

## [Unreleased]

---

## [0.2.0] — Tooling & Build Hardening

### Added
- `COMMANDS.md` documenting Makefile-based cluster operations
- devbox configuration for local agentic development
- git hooks for automatic formatting and compilation checks
  - `.githooks/pre-commit` - runs spotless formatting check before commit
  - `.githooks/pre-push` - runs compilation check before push
- `CHANGELOG.md` documenting changes to the project
- `config/eclipse-formatter.xml` - Eclipse Java formatter configuration for consistent code style
- `maven-compiler-plugin` added to all modules with Lombok annotation processor support
- `spotless-maven-plugin` (v2.43.0) added to all modules for automated code formatting
- Eclipse formatter integration via Spotless for consistent code formatting across the project

### Changed
- `Makefile` now uses `mvn spotless:apply` to apply the formatting
- `Makefile` now uses `mvn spotless:check` to check for formatting errors
- Root `pom.xml` updated with `maven-compiler-plugin` in pluginManagement (v3.11.0)
- Root `pom.xml` updated with `spotless-maven-plugin` in pluginManagement (v2.43.0)
- All module `pom.xml` files updated to include compiler and spotless plugins
- Reformatted all Java source files (75 files) to comply with Eclipse formatter rules
- Updated Java compiler configuration to use `maven.compiler.release` property (Java 21)

### Fixed
- Reformatted code to comply with Spotless formatting rules
- Consistent code formatting across all modules

---

## [0.1.0] — Initial Architecture Baseline

### Added
- Lucene-based indexing and search core
- Distributed architecture with:
  - Coordinator node
  - Gateway
  - Query node(s)
  - Index node(s)
- gRPC-based internal communication
- HTTP/gRPC Gateway entrypoint
- BM25 lexical search support
- Initial shard-based index layout
- Basic cluster lifecycle scripts:
  - `run_cluster.sh`
  - `run_cluster_multi.sh`
  - `kill_cluster.sh`
- Makefile for local development and cluster orchestration

### Design Notes
- Query and Index nodes are intentionally separated to allow independent scaling
- Coordinator maintains cluster metadata but does not sit on the query hot path
- System favors availability and partial results over strict consistency

---

## Changelog Guidelines

When updating this file:
- Add new entries under **[Unreleased]**
- Group changes under **Added / Changed / Fixed / Removed**
- Move entries to a versioned section when cutting a release
- Include architectural changes, not just code churn

---

## Versioning Notes

This project is currently **pre-1.0**:
- APIs may change
- Architecture may evolve
- Backward compatibility is not guaranteed

Breaking changes should still be clearly documented.