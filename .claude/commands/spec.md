Generate a feature specification for: $ARGUMENTS

This is the "specification only" workflow — it produces the spec and stops. It does NOT create a
branch or start implementing (use `/feature` for the full workflow).

1. Delegate to the `project-manager-docs` agent to create a specification document at
   `docs/specs/YYYY-MM-DD-feature-name.md` (use today's date, convert the feature name to
   kebab-case).
2. The spec MUST include: Overview, Goals, User Stories (with acceptance criteria),
   Technical Approach, Out of Scope, Dependencies, and Risks.
3. Present the spec to the user for review. Do NOT create a branch or write any implementation
   code — when the user is ready to build it, they can run `/feature` referencing this spec.

Start by launching the project-manager-docs agent now.
