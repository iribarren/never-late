Create a Pull Request from the current branch to master.

Steps:

1. Run `git status` and `git branch --show-current` to confirm you are NOT on `master`. If on master, stop and tell the user to switch to a feature or bugfix branch first.

2. Run `git log master..HEAD --oneline` to list all commits on this branch.

3. Run `git diff master...HEAD --stat` to summarize changed files.

4. Look for a related spec in `docs/specs/` — search for a file whose name matches the branch name (e.g., branch `feature/user-auth` → look for a spec containing `user-auth`). Use `ls docs/specs/` to list available specs. If a matching spec is found, note its path for the PR body.

5. Build the PR title from the branch name:
   - Strip the `feature/` or `bugfix/` prefix
   - Convert hyphens to spaces
   - Capitalize appropriately
   - Keep it under 70 characters

6. Create the PR with `gh pr create` using this body format:

```
## Summary
- <bullet summarizing main change 1>
- <bullet summarizing main change 2>
- <bullet summarizing main change 3 if needed>

## Spec
<Link to docs/specs file if found, otherwise "N/A">

## Test plan
- [ ] <test step 1>
- [ ] <test step 2>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

7. Output the PR URL when done.
