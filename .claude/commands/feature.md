Start the new feature workflow for: $ARGUMENTS

Follow the Mandatory Workflow for new features defined in CLAUDE.md:

1. **Ask about the tutorial FIRST.** Before writing or delegating anything, use `AskUserQuestion` to
   ask whether this feature carries a Spanish tutorial lesson. Options: **Sí, con lección** /
   **No (solo producto)** / **Decidir al final**. Do not assume — plenty of features are product
   change that teaches nothing new. See *Tutorial Track (optional)* in CLAUDE.md.
2. Delegate to the `project-manager-docs` agent to create a feature specification document at
   `docs/specs/YYYY-MM-DD-feature-name.md` (use today's date, convert the feature name to
   kebab-case). Pass the answer from step 1 so the spec records it in its `Tutorial:` field.
3. Present the spec to the user and wait for explicit approval before proceeding — approval covers
   behaviour, look, and the tutorial decision.
4. After approval, create a `feature/<name>` branch from `master` in the appropriate repo(s)
5. Proceed to implementation with the appropriate agent(s), then tests, then the design review.
   Meet the **Definition of Done** in CLAUDE.md before committing.

Start by asking the tutorial question now.
