#!/bin/bash
# Re-render the v2 mockups -> mockup2a.png / mockup2b.png
cd "$(dirname "$0")"
EDGE="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
for n in 2a 2b; do
  "$EDGE" --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=1.5 --window-size=1380,930 \
    --screenshot="$(cygpath -w "$PWD/mockup$n.png")" "file:///$(cygpath -m "$PWD/mockup$n.html")" >/dev/null 2>&1
done
