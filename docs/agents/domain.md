# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root.
- **`doc/adr/`** — read ADRs that touch the area you're about to work in.

This repo is **single-context**. There is no `CONTEXT-MAP.md`.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The producer skill (`/grill-with-docs`) creates them lazily when terms or decisions actually get resolved.

## File structure

```
/
├── CONTEXT.md
├── doc/
│   ├── adr/
│   │   └── 0001-<decision>.md
│   ├── mc-friends-api-recon.md        ← reverse-engineering notes (raw, partly superseded)
│   └── 好友功能移植-进度与待办.md      ← progress + open questions
├── docs/
│   └── agents/                        ← this skill's config
├── wiki/src/                          ← published specs (MdBook)
│   ├── zh/Yggdrasil-服务端技术规范.md
│   └── zh/好友功能技术规范.md
└── src/
```

Note the two similarly-named directories, which are **not** interchangeable:

- **`doc/`** — working notes and (once created) ADRs. Not published.
- **`docs/`** — agent-skill configuration only (`docs/agents/`).

## Authoritative sources

When these disagree, the more recently verified wins:

- `wiki/src/zh/好友功能技术规范.md` — the **normative** server contract for the friends
  feature. Verified by decompiling authlib 10.0.77 / MC 26.3.
- `doc/mc-friends-api-recon.md` — early reconnaissance. **Known to contain errors**
  (presence request semantics, the HTTP method of `updateAttributes`, a missed
  `Retry-After`-driven poll interval, and stale file paths). Treat it as history, not
  as spec.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/grill-with-docs`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (…) — but worth reopening because…_
