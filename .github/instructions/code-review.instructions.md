---
applyTo: "**"
---

# Code Review Guidelines

Focus on high-signal issues requiring judgment — do not duplicate what CI enforces.

## What CI Enforces (Do NOT Comment On)

- Formatting, trailing commas, line length, import order → Spotless + ktlint
- Static analysis (long methods, complexity, naming) → Detekt with maxIssues=0
- Line coverage thresholds → JaCoCo ≥80% per module

## Blocking Issues (Must Fix Before Merge)

### Architecture Violations

- Vehicle modules (`vehicle-*`) must expose only interfaces publicly. Flag any `public` or non-`internal` concrete implementation class.
- Library modules must NOT use `@InstallIn` on Hilt modules whose bindings differ by flavor. Flavor-varying bindings belong in `:app` source sets `src/mock/` and `src/real/` (ADR-010).

### Security

- No secrets, tokens, credentials, or private keys in code, configs, docs, or logs.
- No hardcoded environment-specific values (paths, IPs, hostnames).

## Quality Issues (Should Fix)

### Testing

- JUnit 5 is default. JUnit 4 permitted ONLY for tests requiring Robolectric.
- Mock only external boundaries (VHAL, network, persistence) — never mock app-internal interfaces (ADR-010 edge-only mock principle).

### DI Patterns

- Bindings identical across mock and real flavors belong in `app/src/main/` — not duplicated.

### Language

- All repository artifacts must be in English. Flag any non-English text.

## Review Tone

- Be specific and actionable. Cite the relevant ADR number.
- Suggest concrete fixes, not just problems.
- Separate blocking issues from quality improvements.
