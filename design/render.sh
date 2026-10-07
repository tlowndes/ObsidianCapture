#!/bin/bash
# Re-render mockup.html -> mockup.png (edit the CSS variables in mockup.html to iterate)
cd "$(dirname "$0")"
"/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" --headless --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=2 --window-size=1000,980 --screenshot="$(cygpath -w "$PWD/mockup.png")" "file:///$(cygpath -m "$PWD/mockup.html")"
