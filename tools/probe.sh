#!/usr/bin/env bash
# Prints parts of the game's API and resources into the CI log, as asked for in tools/probe.txt.
# (The game cannot be compiled against outside CI, so this is how its exact signatures are looked up.)
# Lines of probe.txt:  src <path in the sources jar> | srcdir <regex> | grepsrc <path> <regex>
#                      list <regex> | asset <path> | assetdir <regex> | alist <regex> | javap <class>
set -u
JAR=$(ls build/moddev/artifacts/*.jar 2>/dev/null | grep -v sources | grep -i patched | head -1)
SRC=$(ls build/moddev/artifacts/*.jar 2>/dev/null | grep sources | head -1)
echo "jar: $JAR"; echo "sources: $SRC"
while read -r kind a b; do
  case "$kind" in
    src)     echo "API-BEGIN src $a"; unzip -p "$SRC" "$a" 2>/dev/null || echo "(missing)"; echo "API-END" ;;
    srcdir)  for f in $(unzip -Z1 "$SRC" | grep -E "$a"); do echo "API-BEGIN src $f"; unzip -p "$SRC" "$f"; echo "API-END"; done ;;
    grepsrc) echo "API-BEGIN grepsrc $a"; unzip -p "$SRC" "$a" 2>/dev/null | grep -n -E "$b" || echo "(nothing)"; echo "API-END" ;;
    list)    echo "API-BEGIN list $a"; unzip -Z1 "$SRC" | grep -E "$a" || echo "(nothing)"; echo "API-END" ;;
    asset)   echo "API-BEGIN asset $a"; unzip -p "$JAR" "$a" 2>/dev/null || echo "(missing)"; echo "API-END" ;;
    assetdir) for f in $(unzip -Z1 "$JAR" | grep -E "$a"); do echo "API-BEGIN asset $f"; unzip -p "$JAR" "$f"; echo "API-END"; done ;;
    alist)   echo "API-BEGIN alist $a"; unzip -Z1 "$JAR" | grep -E "$a" || echo "(nothing)"; echo "API-END" ;;
    javap)   echo "API-BEGIN javap $a"; javap -cp "$JAR" -public "$a" 2>&1 | head -400; echo "API-END" ;;
  esac
done < tools/probe.txt
