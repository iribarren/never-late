Generate a feature specification for: $ARGUMENTS

This is the "specification only" workflow — it produces the spec and stops. It does NOT create a
branch or start implementing (use `/feature` for the full workflow).

1. **Ask about the tutorial first**: use `AskUserQuestion` to ask whether this feature carries a
   Spanish tutorial lesson (**Sí, con lección** / **No (solo producto)** / **Decidir al final**).
   See *Tutorial Track (optional)* in CLAUDE.md — a lesson is optional, never assumed.
2. Delegate to the `project-manager-docs` agent to create a specification document at
   `docs/specs/YYYY-MM-DD-feature-name.md` (use today's date, convert the feature name to
   kebab-case).
3. The spec MUST use the project's single template — the same one `/feature` uses:
   - **Overview** — the problem and the intended outcome.
   - **Tutorial** — `sí (lección NN-slug)` / `no` / `decidir al final`, from step 1.
   - **User Stories**
   - **Acceptance Criteria** — behavioural AND visual, each concrete and checkable.
   - **Visual & UX Design** — the slice of `docs/mockups/rediseno-ux-ui.html` this feature
     implements, what visual polish is explicitly deferred and to where, and which existing theme
     tokens / components (`ui/theme/`, `ui/components/`) it reuses instead of inventing new styling.
   - **Out of Scope**
   - **Dependencies**
   - **Risks**
4. Present the spec to the user for review. Do NOT create a branch or write any implementation
   code — when the user is ready to build it, they can run `/feature` referencing this spec.

Start by asking the tutorial question now.
