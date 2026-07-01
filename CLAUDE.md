# Never Late Again — Workspace

<!-- Installed by setup-claude.sh — project type: mobile · agents: project-manager-docs, qa-engineer, devops-security-engineer, mobile-engineer -->

> Template. Replace every `<...>` placeholder and delete guidance you don't need.
> The **Mandatory Workflow**, **Branch Rules** and **Execution Policy** sections below are what the
> exported commands (`/feature`, `/bugfix`, `/pr`, `/spec`, `/test`, `/doc-check`) and hooks rely
> on — keep them roughly intact so the tooling behaves as designed.

## Overview
Tutorial project that develops an app to help people with ADHD to better manage their time

## Structure
<Describe the app module layout once scaffolded — e.g. screens/views, navigation, state,
API client, and platform-specific code. Fill this in after the MVP scaffolding exists.>

## Development

```bash
<how to run the app locally, e.g. flutter run / npx react-native run-ios / open in Xcode or Android Studio>
```

| Target | How to run |
|--------|-----------|
| Android emulator | <command> |

> If the app talks to a backend API, record its base URL per environment (dev/staging/prod).

## Key Conventions
- All code (variables, functions, comments, DB fields) MUST be in English.
- If the project has a backend, data persistence and sensitive logic must live there, not in the client.
- Security is an MVP requirement, not a stretch goal.
- <Add project-specific conventions, domain glossary, naming rules here.>

## API Contract (fullstack / API + client)

> Keep this section only if the project has a backend API and a client that consumes it
> (fullstack, or an API paired with a separate frontend/mobile client). Delete it otherwise.

When a backend and a client grow together, the API contract is the coupling point — treat it as a
single source of truth so a change on one side surfaces on the other at build time, not in
production.

- **The OpenAPI/API spec is the single source of truth for endpoints.** `backend-engineer` updates
  the spec in the SAME change as any endpoint change (request/response shapes, status codes, auth,
  error format). Do NOT list endpoints in any README.
- **Generate the client, don't hand-write it.** The client's API types/SDK are generated from the
  spec (e.g. `openapi-typescript`, `orval`, `openapi-generator`). `frontend-engineer` /
  `mobile-engineer` consume the generated types — never redeclare request/response shapes by hand,
  so a breaking API change becomes a compile error in the client.
- **One feature, one spec, one branch.** A change that spans the API and the client is specified
  once (`docs/specs/…`), implemented on a single `feature/<name>` branch, and reviewed as one PR —
  never merged half-and-half across sides.
- **Regenerate before wiring up.** After the spec changes, regenerate the client types before
  editing client code that calls the new/changed endpoint.
- **Verify against the real integration.** Bring both sides up together (e.g. `docker compose up`)
  so `/test` and manual checks exercise the actual client↔API path, not mocks alone.

## Execution Policy
- NEVER run anything in a scratch/temporary directory (e.g. `/tmp/...`), and NEVER execute
  commands, tests, installs, or tooling outside the project folder.
- All commands MUST run inside the project tree, or inside the project's own containers
  (`docker compose exec ...`).
- If a constraint prevents running something in place (permissions on `node_modules`, an unsuitable
  container image, a missing dependency, a missing browser), STOP. Do not work around it with a
  scratch directory or an external location. Report the blocker and propose fixes (correct file
  ownership, adjust the image/service, add the dependency, change config) and wait for the user to
  decide.

## Mandatory Workflow

### New Feature Workflow
When the user requests a new feature or enhancement, ALWAYS follow this sequence:

1. **Specification first**: Delegate to the `project-manager-docs` agent to define the feature. The
   spec is saved in `docs/specs/YYYY-MM-DD-feature-name.md`. Must include: Overview, User Stories,
   Acceptance Criteria, Out of Scope, Dependencies.
2. **User approval**: Present the spec. Do NOT proceed until the user explicitly approves.
3. **Create feature branch**: `feature/<short-name>` from `<main-branch>` in the appropriate repo.
4. **Implement**: Use the appropriate agent(s).
5. **Test**: Use `qa-engineer` to create/update tests.
6. **Commit on the feature branch**: Never directly on `<main-branch>`.

### Bug Fix Workflow
When the user reports a bug:

1. **Diagnose**: Understand the bug.
2. **Create bugfix branch**: `bugfix/<short-name>` from `<main-branch>`.
3. **Fix and test**.
4. **Commit on the bugfix branch**.

### Branch Rules
- NEVER commit directly to `<main-branch>` (e.g. `master` or `main`).
- Branch naming: `feature/<name>` or `bugfix/<name>`, lowercase, hyphen-separated.
- If already on a feature/bugfix branch, continue on it.

> Note: the `check-branch.sh` hook enforces this by blocking source edits on the main branch.
> Adjust `MAIN_BRANCHES` in that hook if your default branch is not `master`/`main`.

### Documentation Update (mandatory before committing)

Every PR that changes observable behaviour MUST update the relevant documentation in the same
branch. Check each item that applies (run `/doc-check` to audit this against your diff):

- **New/removed/changed API endpoint?** → Update the OpenAPI/API spec (annotations or spec file).
  The generated API doc is the single source of truth for endpoints — do NOT list endpoints in the
  README.
- **Setup/commands/ports/services changed?** → Update the root `README.md` and infra/compose docs.
- **Frontend stack or structure changed?** → Update the frontend `README.md` / `CLAUDE.md`.
- **New environment variable?** → Add it to the env-vars reference (e.g. `docs/env-vars*.md`).
- **Deployment/infra changed?** → Update the deployment docs.
- **New sub-project/package?** → Add it to the project table in the root `README.md` / `CLAUDE.md`.
- **Security headers / CSP changed?** → Update the relevant headers config.
