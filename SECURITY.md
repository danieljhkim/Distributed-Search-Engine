# Security Policy

## Supported Versions

The following versions of DSearch are currently supported with security updates:

| Version | Supported |
|--------|-----------|
| latest | ✅ |
| stable release tags (vX.Y.Z) | ✅ |
| release candidates (vX.Y.Z-rc*) | ⚠️ Best-effort |
| older versions | ❌ |

Release candidates (RCs) are intended for testing and validation and may not receive timely security fixes.

---

## Reporting a Vulnerability

If you discover a security vulnerability in DSearch, **please do not open a public GitHub issue**.

Instead, report it responsibly using one of the following methods:

- **GitHub Security Advisories** (preferred)
  - Go to the repository
  - Click **Security → Advisories → New draft advisory**

Please include:
- A clear description of the issue
- Steps to reproduce (if applicable)
- Potential impact
- Affected components or services

We aim to acknowledge reports within **72 hours** and will coordinate disclosure once a fix is available.

---

## Security Scope

DSearch is a distributed system composed of multiple services (gateway, coordinator, query nodes, index nodes).

In-scope vulnerabilities include:
- Remote code execution (RCE)
- Authentication or authorization bypass
- Data leakage or corruption
- Denial-of-service vulnerabilities
- Dependency vulnerabilities that materially affect runtime security

Out of scope:
- Issues requiring local root access
- Denial-of-service via unbounded legitimate traffic
- Vulnerabilities in unsupported versions

---

## Best Practices for Users

We recommend:
- Running DSearch services inside a private network
- Restricting access to the Gateway API
- Using TLS for all inter-node communication in production
- Pinning Docker images by digest (avoid mutable tags such as `:latest` in production)

## Container Runtime Policy

The published containers use the dedicated numeric identity `10001:10001`, a
read-only root filesystem, `no-new-privileges`, and no Linux capabilities.
The Compose production profile makes only these paths writable:

| Service | Writable path | Storage | Purpose |
|---------|---------------|---------|---------|
| coordinator | `/data` | persistent volume | topology state and its recovery copy |
| query node | `/var/cache/dsearch` | persistent volume | DJL model and engine cache |
| index node | `/data/index` | persistent volume | Lucene shard data |
| index node | `/var/cache/dsearch` | persistent volume | DJL model and engine cache |
| every service | `/tmp` | size-limited `noexec`, `nosuid`, `nodev` tmpfs | JVM temporary files |

TLS identities are mounted read-only at `/etc/dsearch/tls`; production private
keys should be owned by group `10001` with mode `0640` (or supplied by a secret
store with equivalent access). The gateway has no persistent writable mount. A
deployment that adds another writable path or a Linux capability is outside the
supported hardened profile and must document the need in its own threat model.

## Release Vulnerability and Evidence Policy

The tag-triggered release gate scans each exact multi-architecture image digest
with Trivy. Any `CRITICAL` operating-system or application vulnerability blocks
promotion, including vulnerabilities without a published fix. Lower severities
are retained in the scan output for triage and dependency-update planning.

Exceptions are empty by default in `.trivyignore.yaml`. An exception requires a
security-reviewed pull request that records the vulnerability ID, owner,
justification, linked advisory, and an `expired_at` date no more than 30 days in
the future. Expired exceptions stop being honored by Trivy. Blanket package,
path, or severity exclusions are not permitted.

Every released digest must also retain all of the following mutually consistent
evidence: source tag and commit, Maven version, image version label, SPDX SBOM,
GitHub build-provenance attestation, Cosign keyless signature, Maven verification
result, and the hardened Docker end-to-end result. The workflow refuses an
existing version tag that points to a different digest and promotes the tested
manifest rather than rebuilding it. Docker base tags remain human-readable but
are locked by multi-architecture digest; Dependabot proposes reviewed digest
updates for each Dockerfile.

---

## Disclosure Policy

We follow responsible disclosure practices:
- Vulnerabilities are fixed before public disclosure when possible
- Security advisories are published for confirmed issues
- Credit is given to reporters unless anonymity is requested

Thank you for helping keep DSearch secure.
