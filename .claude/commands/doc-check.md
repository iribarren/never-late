Check that mandatory documentation was updated for the changes on this branch.

Run this before committing / opening a PR. It verifies the "Documentation Update" checklist from
CLAUDE.md against the actual diff and reports what is missing — it does not commit anything.

Steps:

1. List the changed files: `git diff master...HEAD --stat` (fall back to `main` if there is no
   `master`). Also include uncommitted changes with `git status`.
2. For each category below, if the diff touches it, confirm the matching documentation was updated
   in the SAME branch. Report each as ✅ done, ⚠️ missing, or ➖ not applicable:
   - **API endpoint added/removed/changed?** → OpenAPI/API spec updated (annotations or spec file).
     The generated API doc is the source of truth; the README must NOT list endpoints.
   - **Setup / commands / ports / services changed?** → root `README.md` and any `compose*.yaml`
     docs updated.
   - **Frontend stack or structure changed?** → frontend `README.md` / `CLAUDE.md` updated.
   - **New environment variable?** → documented in the env-vars reference (e.g.
     `docs/env-vars*.md`).
   - **Deployment / infrastructure changed?** → deployment docs updated.
   - **New sub-project / package?** → added to the project overview table in `README.md` /
     `CLAUDE.md`.
3. Output a short checklist summary. If anything is ⚠️ missing, list the exact file(s) that should
   be updated and stop so the user can decide.

Start by listing the changed files now.
