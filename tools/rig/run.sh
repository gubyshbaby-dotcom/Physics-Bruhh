#!/usr/bin/env bash
# The gas solver's compute shaders without the game (GasCompute and what it runs on), to see that they
# give the numbers of the Rust solver and to time a step on this machine's graphics card.
#
#   tools/rig/run.sh <folder with the jars of LWJGL 3.4> vulkan|opengl [scene] [steps] [coarse cells to a block] [fine cells to a coarse one]
#
# The jars: lwjgl, lwjgl-vulkan, lwjgl-vma, lwjgl-shaderc, lwjgl-opengl, lwjgl-glfw and their natives
# (the release archive of LWJGL has them all). Scenes: fire, camp, fires, far. "opengl" needs a display.
set -eu
jars=$1; kind=$2; shift 2
here=$(cd "$(dirname "$0")/../.." && pwd)
cp=$(ls "$jars"/*.jar | tr '\n' ':')
out=$(mktemp -d)
main=$here/src/main/java/physicsbruhh
javac -cp "$cp" -d "$out" "$here"/tools/rig/src/rig/*.java "$here"/tools/rig/src/physicsbruhh/client/*.java \
  "$main"/client/GasBox.java "$main"/client/GasCompute.java "$main"/client/GasComputeVulkan.java "$main"/client/GasComputeOpenGl.java \
  "$main"/client/compute/VkCompute.java "$main"/sim/GasParams.java
case "$kind" in
  vulkan) class=physicsbruhh.client.ComputeRig ;;
  opengl) class=physicsbruhh.client.GlRig ;;
  *) echo "vulkan or opengl?"; exit 2 ;;
esac
java -Dshaders="$here/src/main/resources/assets/physicsbruhh/shaders" -cp "$out:$cp" "$class" "$@"
