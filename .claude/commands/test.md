Write or expand tests for: $ARGUMENTS

Use this after implementing or changing behaviour, to satisfy the test-coverage reminder emitted
by the `check-tests` Stop hook.

1. Identify what changed. If `$ARGUMENTS` is empty, inspect the current diff
   (`git diff` and `git diff --staged`) to determine which code needs coverage.
2. Delegate to the `android-engineer` agent to create or update tests that cover the new/changed
   behaviour — happy path, edge cases, and error handling. Tell it explicitly that this is a
   **test-only pass**: it must not change implementation code except to fix a bug its own new tests
   expose, and it should report any such bug rather than quietly widening scope.
3. Run the suite once, in the foreground, and report the real counters:

   ```bash
   timeout 600 ./gradlew :app:testDebugUnitTest --console=plain
   ```

   Do not run it while the subagent is still working — see **Build & test execution** in CLAUDE.md.
   If tests fail, report the failure output; do not claim success unless the suite is green.

Start by determining the scope of the change now.
