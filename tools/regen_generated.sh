#!/bin/bash
# Re-sync the vendored engine from a tv-decomp checkout and rebuild its table
# images. Run deliberately when the pin moves, then commit the result: Gradle
# never runs this.
#
#   TVDECOMP=/path/to/tv-decomp bash tools/regen_generated.sh
#
# Upstream keeps each language in its own tree (lang/enu, lang/spa, lang/jpn,
# lang/eng), so only those are copied; gui, sapi5, harness and tools are not
# the synthesizer, and lang/jpn/data is the 27 MB dictionary the Japanese front
# end loads from disk at runtime, which this app does not ship.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UP="${TVDECOMP:?set TVDECOMP to a tv-decomp checkout}"
V="$ROOT/app/src/main/cpp/vendor"
GEN="$V/generated"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

rm -rf "$V/src" "$V/include" "$V/lang" "$GEN"
mkdir -p "$V/lang/spa" "$V/lang/eng" "$V/lang/jpn" "$GEN"
cp -r "$UP/src" "$V/src"
cp -r "$UP/include" "$V/include"
cp -r "$UP/lang/spa/engine" "$UP/lang/spa/port" "$V/lang/spa/"
cp -r "$UP/lang/eng/port" "$UP/lang/eng/data" "$V/lang/eng/"
cp -r "$UP/lang/jpn/port" "$V/lang/jpn/"

# Struct headers and the Spanish rename map are pure functions of the sources.
python3 "$UP/tools/gen_struct.py" "$UP/src/engine.fields" "$GEN/engine_struct.h"
python3 "$UP/tools/gen_struct.py" "$UP/lang/spa/engine/engine.fields" \
  "$GEN/es_engine_struct.h"
(cd "$UP" && python3 tools/gen_rename.py lang/spa/engine,lang/spa/port es_ \
  "$GEN/es_rename.h")

# The table images. gen_data lays them out from host object files and writes
# GAS; the NDK assembles that for every ABI, so unlike iTruVoice there is no
# Mach-O translation step here.
mkdir -p "$TMP/obj" "$TMP/es"
for f in "$UP"/src/engine/*.c "$UP"/src/syn_hifi.c "$UP"/src/port/api.c \
         "$UP"/src/port/tvtts.c "$UP"/src/port/msvcrt.c "$UP"/src/port/stubs.c; do
  gcc -O2 -w -I"$UP/src" -I"$UP/include" -I"$UP/lang/eng/data" -I"$GEN" \
    -c "$f" -o "$TMP/obj/$(basename "$f" .c).o"
done
python3 "$UP/tools/gen_data.py" "$UP/lang/enu/data/engine.tvdata" "$UP/src" \
  "$GEN/tvdata.s" "$TMP"/obj/*.o

for f in "$UP"/lang/spa/engine/*.c "$UP"/lang/spa/port/*.c; do
  gcc -O2 -w -I"$UP/lang/spa/engine" -I"$UP/src" -I"$UP/include" -I"$GEN" \
    -include "$GEN/es_rename.h" -c "$f" -o "$TMP/es/es_$(basename "$f" .c).o"
done
python3 "$UP/tools/gen_data.py" --prefix es_ "$UP/lang/spa/data/engine.tvdata" \
  "$UP/lang/spa/engine,$UP/lang/spa/port" "$GEN/tvdata_es.s" "$TMP"/es/*.o

echo "regenerated:"
ls -la "$GEN"
