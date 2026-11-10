#!/usr/bin/env bash
# Prints parts of the game's API and resources into the CI log, as asked for in tools/probe.txt.
# (The game cannot be compiled against outside CI, so this is how its exact signatures are looked up.)
# Lines of probe.txt:  src <class path ending in .java> | srcdir <regex over class paths> | grepsrc <path> <regex>
#                      list <regex> | asset <path> | assetdir <regex> | alist <regex> | javap <class>
# Sources are decompiled on the spot from the classes asked for; nothing is kept.
set -u
JAR=$(ls build/moddev/artifacts/*.jar 2>/dev/null | grep -v sources | grep -i patched | head -1)
echo "jar: $JAR"
unzip -Z1 "$JAR" > entries.txt
rm -rf probe-in probe-out && mkdir -p probe-in probe-out
# 1. collect the classes that have to be decompiled
: > wanted.txt
while read -r kind a b; do
  case "$kind" in
    src|grepsrc) base="${a%.java}"; grep -E "^${base//\$/\\\$}(\\\$[^/]*)?\.class$" entries.txt >> wanted.txt ;;
    srcdir)      grep -E "$a" entries.txt | grep '\.class$' >> wanted.txt ;;
  esac
done < tools/probe.txt
sort -u wanted.txt -o wanted.txt
echo "classes to decompile: $(wc -l < wanted.txt)"
if [ -s wanted.txt ]; then
  xargs -a wanted.txt unzip -q -o "$JAR" -d probe-in
  curl -sSL -o vineflower.jar https://repo1.maven.org/maven2/org/vineflower/vineflower/1.11.1/vineflower-1.11.1.jar
  java -jar vineflower.jar -e="$JAR" --log-level=warn probe-in probe-out > vineflower.log 2>&1 || tail -20 vineflower.log
  echo "decompiled files: $(find probe-out -name '*.java' | wc -l)"
fi
# 2. print what was asked for, in order
while read -r kind a b; do
  case "$kind" in
    src)     echo "API-BEGIN src $a"; cat "probe-out/$a" 2>/dev/null || echo "(missing)"; echo "API-END" ;;
    srcdir)  for f in $(cd probe-out && find . -name '*.java' | sed 's|^\./||' | grep -E "${a%\\.class*}" | sort); do echo "API-BEGIN src $f"; cat "probe-out/$f"; echo "API-END"; done ;;
    grepsrc) echo "API-BEGIN grepsrc $a"; grep -n -E "$b" "probe-out/$a" 2>/dev/null || echo "(nothing)"; echo "API-END" ;;
    list)    echo "API-BEGIN list $a"; grep -E "$a" entries.txt | grep -v '\$' || echo "(nothing)"; echo "API-END" ;;
    asset)   echo "API-BEGIN asset $a"; unzip -p "$JAR" "$a" 2>/dev/null || echo "(missing)"; echo "API-END" ;;
    assetdir) for f in $(grep -E "$a" entries.txt | grep -v '/$'); do echo "API-BEGIN asset $f"; unzip -p "$JAR" "$f"; echo "API-END"; done ;;
    alist)   echo "API-BEGIN alist $a"; grep -E "$a" entries.txt || echo "(nothing)"; echo "API-END" ;;
    javap)   echo "API-BEGIN javap $a"; javap -cp "$JAR" -public "$a" 2>&1 | head -400; echo "API-END" ;;
  esac
done < tools/probe.txt
