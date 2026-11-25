#!/usr/bin/env bash
# Rovnaká verzia pre mobil, Windows aj Linux:
# počká, kým v inom workflow (pre ten istý commit) úspešne prejde daný krok; ak zlyhá, toto vydanie sa nespraví.
# použitie: cakaj.sh <workflow.yml> <názov kroku alebo "*" = celý workflow> [max minút]
set -u
WF="$1"; KROK="$2"; MAX="${3:-90}"
for i in $(seq 1 $((MAX * 2))); do
  RUN=$(gh api "repos/$GITHUB_REPOSITORY/actions/workflows/$WF/runs?head_sha=$GITHUB_SHA&per_page=1" --jq '.workflow_runs[0] | "\(.id) \(.status) \(.conclusion)"' 2>/dev/null)
  read -r ID STAV VYSL <<< "$RUN"
  if [ -n "${ID:-}" ] && [ "$ID" != "null" ]; then
    if [ "$KROK" = "*" ]; then
      if [ "$STAV" = "completed" ]; then
        [ "$VYSL" = "success" ] && { echo "$WF: OK"; exit 0; }
        echo "::error::$WF pre tento commit zlyhal ($VYSL) – vydanie sa nespraví, aby mobil a PC mali rovnakú verziu"; exit 1
      fi
    else
      K=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$ID/jobs" --jq ".jobs[].steps[] | select(.name == \"$KROK\") | \"\(.status) \(.conclusion)\"" 2>/dev/null | head -1)
      case "$K" in
        "completed success") echo "$WF / $KROK: OK"; exit 0 ;;
        "completed "*) echo "::error::$WF / $KROK zlyhal – vydanie sa nespraví, aby mobil a PC mali rovnakú verziu"; exit 1 ;;
      esac
      [ "$STAV" = "completed" ] && [ "$VYSL" != "success" ] && { echo "::error::$WF zlyhal ($VYSL)"; exit 1; }
    fi
  fi
  sleep 30
done
echo "::error::$WF sa nedokončil včas"; exit 1
