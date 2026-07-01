Install this Claude Code configuration into a target project.

Argument: $ARGUMENTS — optionally the absolute path to the target project root (defaults to the
current project directory), and/or `--name "Project Name"` and `--type TYPE`.

The installer is **project-type aware**. The type selects which agents and which stack permissions
are installed; commands and hooks are always installed. Valid types:
`frontend | backend | api | mobile | game | fullstack | desktop`.

Every type installs the common agents (`project-manager-docs`, `qa-engineer`,
`devops-security-engineer`) plus the type-specific engineer agent(s). The project name is written
into the generated `CLAUDE.md`.

Steps:

1. Resolve the target directory from `$ARGUMENTS` (or use the current project root).
2. Determine the project name and type. If the user supplied them (e.g. `--name`, `--type`, or in
   their message), pass them as flags. Otherwise run the installer so it prompts interactively:
   `bash <path-to>/claude-bootstrap/setup-claude.sh <target-dir> --name "<name>" --type <type>`
3. Report what was created/updated (which agents were copied, which permissions were added). Remind
   the user to review the remaining `<...>` placeholders in `CLAUDE.md` and the
   `.claude/settings.json` permissions.

Start by resolving the target directory, name, and type, then run the installer now.
