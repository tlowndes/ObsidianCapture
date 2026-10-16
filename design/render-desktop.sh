#!/bin/bash
# Re-render the desktop mockups -> desktop1.png ... desktop5.png
# Edge can hand off to an already-running copy and return early, so wait for each file and retry.
cd "$(dirname "$0")"
EDGE="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
for f in desktop[0-9].html; do
  n="${f%.html}"; rm -f "$n.png"
  for try in 1 2 3 4 5; do
    "$EDGE" --headless --disable-gpu --hide-scrollbars --window-size=1600,1000 \
      --screenshot="$(cygpath -w "$PWD/$n.png")" "file:///$(cygpath -m "$PWD/$f")" >/dev/null 2>&1
    for w in 1 2 3 4 5; do [ -s "$n.png" ] && break 2; sleep 1; done
  done
  [ -s "$n.png" ] && echo "rendered $n.png" || echo "FAILED $n.png"
done
