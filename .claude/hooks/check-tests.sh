#!/bin/bash
# Hook: Stop
# If the last assistant turn edited source files (multi-stack) without running
# tests or delegating to the qa-engineer agent, prints a reminder.
# Outputs nothing when the condition is not met.
#
# Tuned to this project: Kotlin sources and Gradle test tasks. It started life as a portable
# web-stack version, which meant it never matched a real run here ('./gradlew test' is not the
# command this project uses) and so nagged on every turn — a reminder that always fires is noise,
# not signal.

# NOTE: the script is passed via `python3 -c "$SCRIPT"` (not `python3 - << EOF`) so that the
# hook's stdin stays the JSON payload piped in by Claude Code, not the heredoc itself.
SCRIPT=$(cat << 'PYEOF'
import json, sys

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

# Support both 'transcript' and 'messages' keys
transcript = data.get('transcript') or data.get('messages') or []

# Find the last assistant message
last_assistant = None
for msg in reversed(transcript):
    if isinstance(msg, dict) and msg.get('role') == 'assistant':
        last_assistant = msg
        break

if not last_assistant:
    sys.exit(0)

content = last_assistant.get('content', [])
if isinstance(content, str):
    sys.exit(0)

SOURCE_EXTS = ('.kt', '.kts')
# Match how tests are actually invoked in this project. Plain 'gradlew' covers both the direct
# './gradlew ...' form and the 'rtk gradlew ...' form the global RTK hook rewrites it into; the
# task names cover the case where a run is scoped with --tests.
TEST_KEYWORDS = ('gradlew', 'testDebugUnitTest', 'connectedDebugAndroidTest')

had_code_edit = False
had_tests = False

for block in content:
    if not isinstance(block, dict) or block.get('type') != 'tool_use':
        continue

    tool = block.get('name', '')
    inp = block.get('input') or {}

    if tool in ('Edit', 'Write'):
        path = inp.get('file_path', '')
        if any(path.endswith(ext) for ext in SOURCE_EXTS):
            had_code_edit = True

    if tool == 'Bash':
        cmd = inp.get('command', '')
        if any(kw in cmd for kw in TEST_KEYWORDS):
            had_tests = True

    if tool in ('Agent', 'Task'):
        subagent = inp.get('subagent_type', '')
        prompt = inp.get('prompt', '')
        if any(a in subagent or a in prompt for a in ('android-engineer', 'qa-engineer')):
            had_tests = True

if had_code_edit and not had_tests:
    print('Consider running the scoped test command or delegating to the android-engineer agent to verify these changes.')
PYEOF
)

python3 -c "$SCRIPT"
