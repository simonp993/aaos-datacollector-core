# OpenSpec Quick Start — Sport Apps

A concise getting-started guide for developers joining this repository. For installation, see the [OpenSpec Setup section in the README](../../README.md#openspec-setup).

---

## What Is OpenSpec?

[OpenSpec](https://github.com/Fission-AI/OpenSpec) is a spec-driven development framework. New features are specified through structured artifacts — proposal, specs, design, tasks — before any code is written. The AI coding assistant creates and manages these artifacts via slash commands.

This project uses an **expanded workflow** with a custom forked schema (`spec-driven-sport-apps`) tailored for AAOS development on the Porsche MIB4 platform.

> **Setup:** Run `pnpm run openspec:init` after cloning — it configures the expanded profile (all 11 workflow commands) and generates AI skills automatically. See the [README](../../README.md#openspec-setup) for full setup instructions.

> **New to OpenSpec?** Read the official [Getting Started](https://github.com/Fission-AI/OpenSpec/blob/main/docs/getting-started.md) and [Concepts](https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md) docs first.

---

## Recommended Workflow

For most feature work, use the **step-by-step path** so you can review and refine each artifact before moving on:

```
explore → new → continue (×4) → apply → verify → archive
```

| Step             | Command                | What Happens                                                                                |
| ---------------- | ---------------------- | ------------------------------------------------------------------------------------------- |
| 1. **Explore**   | `/opsx:explore`        | Think through the problem — investigate codebase, compare approaches. No artifacts created. |
| 2. **Create**    | `/opsx:new my-feature` | Scaffold `openspec/changes/my-feature/` with metadata.                                      |
| 3. **Proposal**  | `/opsx:continue`       | Generate `proposal.md` — the WHY and WHAT. Review, then continue.                           |
| 4. **Specs**     | `/opsx:continue`       | Generate delta specs — testable requirements with scenarios. Review, then continue.         |
| 5. **Design**    | `/opsx:continue`       | Generate `design.md` — the HOW (architecture decisions, risks). Review, then continue.      |
| 6. **Tasks**     | `/opsx:continue`       | Generate `tasks.md` — checkboxed implementation plan. Review, then continue.                |
| 7. **Implement** | `/opsx:apply`          | AI works through tasks, writes code, checks off items.                                      |
| 8. **Verify**    | `/opsx:verify`         | Validate completeness, correctness, and coherence.                                          |
| 9. **Archive**   | `/opsx:archive`        | Sync delta specs to main, move change to archive.                                           |

> **Tip:** You can edit any artifact at any point — update specs if requirements shift, revise the design if implementation surfaces issues, then resume with `/opsx:apply`.

### Other Paths

| Scenario            | Path                                                       | When to Use                           |
| ------------------- | ---------------------------------------------------------- | ------------------------------------- |
| **Quick feature**   | `/opsx:propose` → `/opsx:apply` → `/opsx:archive`          | Small, well-understood changes        |
| **Confident scope** | `/opsx:new` → `/opsx:ff` → `/opsx:apply` → `/opsx:archive` | Clear scope, skip per-artifact review |
| **First time**      | `/opsx:onboard`                                            | Guided walkthrough of the full cycle  |

See the official [Workflows](https://github.com/Fission-AI/OpenSpec/blob/main/docs/workflows.md) and [Commands](https://github.com/Fission-AI/OpenSpec/blob/main/docs/commands.md) docs for the complete reference.

---

## Artifacts at a Glance

| Artifact     | Purpose                                                               | File(s)                      |
| ------------ | --------------------------------------------------------------------- | ---------------------------- |
| **Proposal** | WHY + WHAT — motivation, scope, affected capabilities                 | `proposal.md`                |
| **Specs**    | WHAT the system does — testable requirements with WHEN/THEN scenarios | `specs/<capability>/spec.md` |
| **Design**   | HOW to implement — architecture decisions, risks, trade-offs          | `design.md`                  |
| **Tasks**    | Implementation checklist — checkboxes tracked by the apply phase      | `tasks.md`                   |

Artifacts build on each other: `proposal → specs → design → tasks → implement`. See the official [Concepts](https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md) doc for format details and delta spec mechanics.

---

## Project Customization

This project uses a **forked schema** and project-level configuration rather than the default OpenSpec schema. This section covers what's specific to this repository.

### Our Schema: `spec-driven-sport-apps`

Defined in `openspec/schemas/spec-driven-sport-apps/schema.yaml`. The artifact pipeline:

```
proposal → specs → design → tasks → (apply)
```

`openspec/config.yaml` binds the schema and adds two customization layers:

| Section   | Purpose                                                                                                   |
| --------- | --------------------------------------------------------------------------------------------------------- |
| `context` | Project-wide context injected into all artifact generation (tech stack, Figma access, legacy constraints) |
| `rules`   | Per-artifact rules enforcing project-specific guardrails                                                  |

#### Per-Artifact Rules

| Artifact     | Key Rules                                                                                                                                                                                  |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **proposal** | _(none — constraints are in repo-wide policy)_                                                                                                                                             |
| **specs**    | Figma references via MCP server; pixel-perfect UI screenshots from design spec boards; legacy codebase as behavioral reference only; FRETish variable naming matching SignalBridge signals |
| **design**   | No legacy patterns (Fragment, XML, Dagger 2, ObservableField); target Compose + Hilt + StateFlow; vehicle platform APIs as external contracts only                                         |
| **tasks**    | Three-section ordering (Core → DevOps & Quality → Testing); UI-first with emulator checkpoints; evaluate all conditional task categories; Accessibility + i18n for UI features             |

#### Templates

Scaffolding for each artifact lives in `openspec/schemas/spec-driven-sport-apps/templates/`:

| Template      | Scaffolds                                                                                         |
| ------------- | ------------------------------------------------------------------------------------------------- |
| `proposal.md` | Why / What Changes / Capabilities / Impact                                                        |
| `spec.md`     | ADDED/MODIFIED/REMOVED/RENAMED requirements with scenarios; optional User Flow and UI Composition |
| `design.md`   | Context / Goals & Non-Goals / Decisions / Risks & Trade-offs                                      |
| `tasks.md`    | Checkbox groups — Core Implementation, conditional DevOps & Quality, Testing                      |

### Customization Decision Guide

When adding or modifying AI instructions, place the rule in the correct layer:

**"Applies to ALL AI interactions, regardless of OpenSpec."**
→ `.github/copilot-instructions.md`
_Examples: English-only, ADR binding, no secrets, legacy code constraints._

**"Enforced when a particular artifact is generated."**
→ `openspec/config.yaml` → `rules:` under the matching artifact key.
_Examples: "design must not reference legacy patterns", "tasks must include emulator checkpoints"._

**"Changes what sections an artifact contains, their ordering, or formatting."**
→ `openspec/schemas/spec-driven-sport-apps/schema.yaml` → `instruction:` or `templates/*.md`.
_Examples: new required section in specs, checkbox format in tasks._

**"Project-wide context all artifacts should see."**
→ `openspec/config.yaml` → `context:` section.

**"Changes which artifacts exist or their dependency order."**
→ `openspec/schemas/spec-driven-sport-apps/schema.yaml` → `artifacts:` and `requires:` fields.

**"Changes the apply-phase enforcement rules."**
→ `openspec/config.yaml` → `rules: apply:` section.

#### Common Mistakes

- **Duplicating rules across layers** — wastes AI context and creates drift. A rule belongs in exactly one place.
- **Project-specific rules in `schema.yaml`** — the schema should be reusable; project specifics go in `config.yaml`.
- **Formatting rules in `config.yaml`** — section structure belongs in the schema `instruction:` or templates.
- **Editing generated files** — `.github/prompts/opsx-*.prompt.md` and `.github/skills/openspec-*/SKILL.md` are overwritten by `openspec update`.

### Key Paths

| Resource             | Path                                                  |
| -------------------- | ----------------------------------------------------- |
| Project constitution | `.github/copilot-instructions.md`                     |
| OpenSpec config      | `openspec/config.yaml`                                |
| Schema definition    | `openspec/schemas/spec-driven-sport-apps/schema.yaml` |
| Artifact templates   | `openspec/schemas/spec-driven-sport-apps/templates/`  |
| Main specs           | `openspec/specs/`                                     |
| Active changes       | `openspec/changes/`                                   |
| Example change       | `openspec/changes/make-me-track-ready/`               |
