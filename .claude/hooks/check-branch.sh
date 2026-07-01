#!/bin/bash
# Hook: PreToolUse (Edit|Write)
# Blocks source code edits on the main branch (master/main).
# Config, docs and infrastructure files are allowed on the main branch.
#
# Portable version — no project-specific paths. Adjust MAIN_BRANCHES and the
# allow-list `case` below if your repo uses different conventions.

INPUT=$(cat)

# Branches on which source edits are blocked.
MAIN_BRANCHES="master main"

# Extract file_path from JSON input
FILE_PATH=$(echo "$INPUT" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

# If no file_path found, allow (might be a non-file operation)
if [ -z "$FILE_PATH" ]; then
  exit 0
fi

# Normalize backslashes to forward slashes (Windows paths)
FILE_PATH=$(echo "$FILE_PATH" | tr '\\' '/')

# Get current branch — check the subrepo that owns the file, not the workspace root
BRANCH=$(git -C "$(dirname "$FILE_PATH")" branch --show-current 2>/dev/null)
# Fallback to workspace root if file is not in a git repo
if [ -z "$BRANCH" ]; then
  BRANCH=$(git branch --show-current 2>/dev/null)
fi

# If not on a protected main branch, allow everything
IS_MAIN=false
for b in $MAIN_BRANCHES; do
  if [ "$BRANCH" = "$b" ]; then
    IS_MAIN=true
    break
  fi
done
if [ "$IS_MAIN" = false ]; then
  exit 0
fi

# On the main branch: allow config/docs/infra files, block everything else.
case "$FILE_PATH" in
  *.md)
    # Markdown (README, CLAUDE.md, docs/specs, notes) is documentation — allow.
    exit 0
    ;;
  */.claude/*|*/docs/*|*.env|*.env.*|*.gitignore|*.gitkeep|*.gitattributes|*.editorconfig)
    exit 0
    ;;
  *compose*.yml|*compose*.yaml|*/docker/*|*Dockerfile*|*.dockerignore)
    exit 0
    ;;
  *)
    echo "BLOCKED: You are on '$BRANCH'. Create a feature/ or bugfix/ branch first before editing source code." >&2
    echo "  File: $FILE_PATH" >&2
    echo "  Run: git checkout -b feature/<name>  (or bugfix/<name>)" >&2
    exit 2
    ;;
esac
