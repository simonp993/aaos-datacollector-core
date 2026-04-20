# Agent Governance

Process rules and guardrails for AI agent workflows in this repository.

---

## Scope

- This file applies to all AI agent-driven work in this repository.
- Universal standards (English-only, no secrets, no hardcoding) apply to all artifacts.

---

## Universal Standards

- All repository artifacts MUST be in English (code, comments, docs, commit messages).
- No secrets, tokens, credentials, or private keys in code, configs, docs, or logs.
- Environment-specific values MUST use centralized configuration, not hardcoded.
- Accepted ADRs in `docs/adrs/` are binding constraints on all new code.
- Add or update tests for every behavior change.

---

## Branch and Change Control

- Never push directly to `main` without explicit human approval.
- All changes MUST go through a feature branch and pull request workflow.
- Keep changes focused on the active task.

---

## Commit Message Format

```text
type(scope): Brief description (50 chars)

Longer explanation if needed (72 chars per line):
- Why this change is necessary
- What it impacts

Relates-to: #issue-number
```

Types: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`

---

## Anti-Patterns

- Making broad, implicit rewrites instead of small, reviewable changes
- Diverging from accepted ADRs without explicitly surfacing the conflict
- Hardcoding environment-specific values
- Skipping tests when behavior changes
- Duplicating content instead of linking to canonical sources

---

## Collaboration Expectations

- Ask clarifying questions when requirements are ambiguous.
- Prefer small, reviewable changes over broad rewrites.
- Summarize larger refactors clearly so humans can evaluate intent and impact.

---

**Last Updated**: 2026-04-20
