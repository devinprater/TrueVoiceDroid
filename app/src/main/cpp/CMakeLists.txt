# Compiles the Centigram TruVoice engine via OpenTV's portable C — the
# English engine (src/engine + syn_hifi tables + port layer) and the
# Spanish engine (es/ + es_port), each with its embedded data image
# (generated/tvdata.s, generated/tvdata_es.s).
#
# Tables decision: EMBED. The iOS build verifies byte-identical audio
# against the originals from these same tables; this port is a personal
# build, so the tables ship inside libtruvoicedroid.so and a NOTICE file
# in the app assets + repo root records whose data that is. No network
# fetch of tables, no user-supplied image files: create_lang("en") just
# works. Excluded: gui/, sapi5/, harness/, tools/ (SAPI/COM, test
# harnesses and utilities — none of it is the synthesizer).
cmake_minimum_required(VERSION 3.22.1)
# C for the engine + JNI, ASM for the generated table images
# (generated/tvdata.s, generated/tvdata_es.s — GAS sources the NDK's
# clang assembles for every ABI).
project(truvoicedroid C ASM)

set(VENDOR ${CMAKE_CURRENT_SOURCE_DIR}/vendor)

set(EN_SOURCES
    ${VENDOR}/src/engine/control.c
    ${VENDOR}/src/engine/dict.c
    ${VENDOR}/src/engine/engine.c
    ${VENDOR}/src/engine/feed.c
    ${VENDOR}/src/engine/frame.c
    ${VENDOR}/src/engine/generate.c
    ${VENDOR}/src/engine/input.c
    ${VENDOR}/src/engine/layout.c
    ${VENDOR}/src/engine/lexicon.c
    ${VENDOR}/src/engine/lts.c
    ${VENDOR}/src/engine/node.c
    ${VENDOR}/src/engine/output.c
    ${VENDOR}/src/engine/phonetic.c
    ${VENDOR}/src/engine/preformat.c
    ${VENDOR}/src/engine/ring.c
    ${VENDOR}/src/engine/sapi.c
    ${VENDOR}/src/engine/stage0.c
    ${VENDOR}/src/engine/stage1.c
    ${VENDOR}/src/engine/stage2.c
    ${VENDOR}/src/engine/stage3.c
    ${VENDOR}/src/engine/stages.c
    ${VENDOR}/src/engine/synth.c
    ${VENDOR}/src/engine/textin.c
    ${VENDOR}/src/engine/track.c
    ${VENDOR}/src/engine/volume.c
    ${VENDOR}/src/engine/vowel.c
    ${VENDOR}/src/syn_hifi.c
    ${VENDOR}/src/port/api.c
    ${VENDOR}/src/port/tvtts.c
    ${VENDOR}/src/port/msvcrt.c
    ${VENDOR}/src/port/stubs.c
    ${VENDOR}/generated/tvdata.s
)

set(ES_SOURCES
    ${VENDOR}/es/adjust.c
    ${VENDOR}/es/bittab.c
    ${VENDOR}/es/cluster.c
    ${VENDOR}/es/contour.c
    ${VENDOR}/es/control.c
    ${VENDOR}/es/engine.c
    ${VENDOR}/es/escape.c
    ${VENDOR}/es/flush.c
    ${VENDOR}/es/generate.c
    ${VENDOR}/es/input.c
    ${VENDOR}/es/interp.c
    ${VENDOR}/es/list.c
    ${VENDOR}/es/node.c
    ${VENDOR}/es/number.c
    ${VENDOR}/es/params.c
    ${VENDOR}/es/phone.c
    ${VENDOR}/es/preformat.c
    ${VENDOR}/es/prosody.c
    ${VENDOR}/es/reset.c
    ${VENDOR}/es/ring.c
    ${VENDOR}/es/rule.c
    ${VENDOR}/es/segment.c
    ${VENDOR}/es/stage.c
    ${VENDOR}/es/stage0.c
    ${VENDOR}/es/stage1.c
    ${VENDOR}/es/stage2.c
    ${VENDOR}/es/stage3.c
    ${VENDOR}/es/stage3seg.c
    ${VENDOR}/es/stage4.c
    ${VENDOR}/es/synth.c
    ${VENDOR}/es/tables.c
    ${VENDOR}/es/textin.c
    ${VENDOR}/es/track.c
    ${VENDOR}/es/util.c
    ${VENDOR}/es_port/msvcrt_es.c
    ${VENDOR}/es_port/tvtts_es.c
    ${VENDOR}/generated/tvdata_es.s
)

# English engine + data image. Own library so the Spanish rename
# force-include below touches only Spanish translation units.
add_library(truvoice_en STATIC ${EN_SOURCES})
target_include_directories(truvoice_en PRIVATE
    ${VENDOR}/src
    ${VENDOR}/generated
    ${VENDOR}/include
)
target_compile_options(truvoice_en PRIVATE -O2 -std=gnu11
    # The table image packs symbols with no inter-symbol alignment, and the
    # x86_64 auto-vectorizer emits aligned movaps loads from extern tables
    # (e.g. a byte-loop over g_default_params), which faults where ARM
    # tolerates it. Scalar unaligned loads are safe everywhere; keep the
    # vectorizer off these translation units. No audio change: same
    # operations, different instruction selection.
    -fno-vectorize -fno-slp-vectorize -fno-unroll-loops)

# Spanish engine: every name es_-prefixed via the generated rename header,
# force-included into exactly these translation units.
add_library(truvoice_es STATIC ${ES_SOURCES})
target_include_directories(truvoice_es PRIVATE
    ${VENDOR}/es
    ${VENDOR}/src
    ${VENDOR}/include
    ${VENDOR}/generated
)
target_compile_options(truvoice_es PRIVATE
    -O2 -std=gnu11
    # See truvoice_en above: the packed table image is hostile to the
    # x86_64 vectorizer's aligned loads.
    -fno-vectorize -fno-slp-vectorize -fno-unroll-loops
    "-include${VENDOR}/generated/es_rename.h"
)

add_library(truvoicedroid SHARED truvoice_jni.c)
target_include_directories(truvoicedroid PRIVATE ${VENDOR}/include)
target_compile_options(truvoicedroid PRIVATE -O2 -Wall -std=gnu11)
target_link_libraries(truvoicedroid truvoice_en truvoice_es log)
