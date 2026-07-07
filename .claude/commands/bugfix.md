Start the bug fix workflow for: $ARGUMENTS

Follow the Mandatory Workflow for bug fixes defined in CLAUDE.md:

1. Investigate and reproduce the bug described: $ARGUMENTS
2. Create a `bugfix/<name>` branch from `master` in the appropriate repo
3. Implement the fix
4. Verify the fix at the code level: it must compile, and add/update automated tests where they
   fit. Do **not** attempt the final in-app verification yourself — do not launch the emulator and
   do not connect to a physical device to exercise the app. Confirming the fix behaves correctly in
   the running app is the **user's** responsibility; hand it back to them for that final check.

Start by investigating the bug now.
