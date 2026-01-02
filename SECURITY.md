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
- Pinning Docker images by version (avoid `:latest` in production)

---

## Disclosure Policy

We follow responsible disclosure practices:
- Vulnerabilities are fixed before public disclosure when possible
- Security advisories are published for confirmed issues
- Credit is given to reporters unless anonymity is requested

Thank you for helping keep DSearch secure.