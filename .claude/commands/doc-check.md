Check that mandatory documentation was updated for the changes on this branch.

Run this before committing / opening a PR. It verifies the "Documentation Update" checklist from
CLAUDE.md against the actual diff and reports what is missing — it does not commit anything.

Steps:

1. List the changed files: `git diff master...HEAD --stat`. Also include uncommitted changes with
   `git status`.
2. If there is a spec for this branch in `docs/specs/`, read its **`Tutorial:`** field — it decides
   whether the tutorial item below applies at all.
3. For each category below, if the diff touches it, confirm the matching documentation was updated
   in the SAME branch. Report each as ✅ done, ⚠️ missing, or ➖ not applicable:
   - **Request/response shape, status code or auth changed?** (`backend/src/**`, `data/**` DTOs,
     Retrofit interfaces) → `docs/api/contract.md` updated, and client + server both reconciled to
     it. The contract is the source of truth; endpoints must NOT be re-listed in any README.
   - **Visible UI change?** (`ui/**`, `res/**`) → `docs/mockups/README.md` tracking table updated:
     the slice this feature delivered marked, anything still pending left visible.
   - **New dependency?** → declared in `gradle/libs.versions.toml`. Flag any version hardcoded
     directly in a `build.gradle.kts` as ⚠️.
   - **Room schema changed?** (`@Entity`, `@Database`) → version bumped, a hand-written `Migration`
     added (never `fallbackToDestructiveMigration` — guest tasks live only on-device), the exported
     `app/schemas/<n>.json` committed, and a `MigrationTestHelper` test covering the upgrade.
   - **New permission / manifest change?** → reflected in `CLAUDE.md`.
   - **Setup / commands / SDK / versions changed?** → `CLAUDE.md` (Structure / Development)
     and, for backend run steps, `backend/README.md`.
   - **New module, package or architectural decision worth remembering?** → Structure section of
     `CLAUDE.md` updated, and the decision recorded in `docs/arquitectura.md`.
   - **Tutorial lesson** → applies **only if** the spec's `Tutorial:` field asked for one. If so:
     `tutorial/NN-*.md` exists and its status is ✅ in both `tutorial/README.md` and
     `docs/conceptos-pendientes.md`. If the spec said **no** (or there is no spec), report ➖ — a
     missing lesson is NOT a finding. The tutorial track is optional; see CLAUDE.md.
4. Also sanity-check the **Definition of Done** items that leave a trace in the diff: tests added or
   updated for changed logic, and strings added to BOTH `res/values/strings.xml` (Spanish base) and
   `res/values-en/strings.xml`.
5. Output a short checklist summary. If anything is ⚠️ missing, list the exact file(s) that should
   be updated and stop so the user can decide.

Start by listing the changed files now.
