#!/usr/bin/env bash
# check-docs.sh — assert every backticked repo path in the root orchestrator
# file (AGENTS.md / CLAUDE.md) and .agent/**/*.md still resolves.
#
# A token is checked when it contains "/" or ends in a known file extension.
# Relative tokens resolve against the referencing file's directory, then each
# parent up to the repo root. Not checked:
#   - HTML comments, fenced code blocks, and anything between
#     <!-- check-docs:off --> and <!-- check-docs:on --> (lists of files that
#     must NOT exist, e.g. the never-commit list)
#   - placeholders (<...>), globs, ~/ paths, URLs, tokens with spaces (commands)
#   - absolute tokens outside /Users or /home (treated as URL routes, e.g. /api/x)
#   - archive/ refs and history.md (optional: ADO mode has no history.md)
# Exit 1 on any missing target.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2
fail=0
checked=0

EXT='md|sh|ps1|py|js|jsx|ts|tsx|mjs|vue|svelte|dart|kt|kts|java|gradle|swift|m|h|c|cc|cpp|cs|go|rs|rb|php|sql|yaml|yml|json|toml|xml|plist|arb|html|css|scss|txt|lock|pbxproj|xcconfig|storyboard|entitlements'

tokens() {  # $1=file -> one candidate path per line
  perl -0777 -pe '
    s/<!--\s*check-docs:off\s*-->.*?<!--\s*check-docs:on\s*-->//gs;
    s/<!--.*?-->//gs;
    s/^```.*?^```//gms;
  ' "$1" | grep -oE '`[^`]+`' | tr -d '`' | while IFS= read -r tok; do
    case "$tok" in
      *[[:space:]]*|http*|*://*|\$*|*\"*|*\'*|*\<*|*\>*) continue ;;
      \~*|*\**|*\{*|*\}*|*\?*) continue ;;
      archive/*|*/archive/*|history.md|*/history.md) continue ;;
      /Users/*|/home/*) echo "$tok" ;;
      /*) continue ;;
      */*) echo "$tok" ;;
      *) [[ "$tok" =~ \.($EXT)$ ]] && echo "$tok" ;;
    esac
  done
}

resolves() {  # $1=tok $2=referencing file dir (absolute)
  local tok="$1" d="$2"
  case "$tok" in /*) [ -e "$tok" ]; return ;; esac
  while :; do
    [ -e "$d/$tok" ] && return 0
    [ "$d" = "$ROOT" ] && return 1
    d="$(dirname "$d")"
  done
}

files=()
for r in AGENTS.md CLAUDE.md; do [ -f "$r" ] && files+=("$r"); done
[ ${#files[@]} -eq 0 ] && echo "note: no AGENTS.md/CLAUDE.md at repo root — scanning .agent/ only"
while IFS= read -r -d '' f; do files+=("$f"); done \
  < <(find .agent -name '*.md' -not -path '*/tasks/archive/*' -print0 2>/dev/null)

for f in "${files[@]}"; do
  fdir="$(cd "$(dirname "$f")" && pwd)"
  while IFS= read -r tok; do
    [ -z "$tok" ] && continue
    checked=$((checked+1))
    resolves "$tok" "$fdir" || { echo "MISSING ($f): $tok"; fail=1; }
  done < <(tokens "$f")
done

echo "---"
if [ "$fail" -eq 0 ]; then
  echo "check-docs: PASS — $checked path refs resolved under $ROOT"
else
  echo "check-docs: FAIL — under $ROOT"
fi
exit $fail
