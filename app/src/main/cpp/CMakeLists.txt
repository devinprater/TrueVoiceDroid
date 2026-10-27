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
    ${VENDOR}/src/engine/volume.c
    ${VENDOR}/src/engine/vowel.c
    # OpenTV's own extra voices (Frank for English) and the score compiler,
    # whose file also carries the text-pipeline pieces every utterance runs
    # through (tv_camel_split, tv_phone_commands).
    ${VENDOR}/src/engine/voices.c
    ${VENDOR}/src/port/sing.c
    # General British's vowel targets and stage-1 substitutions; the en-GB row
    # in src/port/api.c calls into it.
    ${VENDOR}/src/engine/gb.c
    ${VENDOR}/src/syn_hifi.c
    ${VENDOR}/src/port/api.c
    ${VENDOR}/src/port/tvtts.c
    ${VENDOR}/src/port/msvcrt.c
    ${VENDOR}/src/port/stubs.c
    ${VENDOR}/generated/tvdata.s
    # The two language ports api.c names unconditionally: British English
    # (wrappers over the American engine) and Japanese (a front end below
    # stage 3). Japanese links but is not offered: its front end needs the
    # 27 MB jadic.bin upstream loads from disk, which this app does not ship.
    ${VENDOR}/lang/eng/port/tvtts_engb.c
    ${VENDOR}/lang/jpn/port/ja_devoice_tab.c
    ${VENDOR}/lang/jpn/port/ja_dict.c
    ${VENDOR}/lang/jpn/port/ja_digit.c
    ${VENDOR}/lang/jpn/port/ja_frame.c
    ${VENDOR}/lang/jpn/port/ja_front.c
    ${VENDOR}/lang/jpn/port/ja_g2p.c
    ${VENDOR}/lang/jpn/port/ja_g2p_tab.c
    ${VENDOR}/lang/jpn/port/ja_mora.c
    ${VENDOR}/lang/jpn/port/ja_njd.c
    ${VENDOR}/lang/jpn/port/ja_ojt.c
    ${VENDOR}/lang/jpn/port/ja_pitch.c
    ${VENDOR}/lang/jpn/port/ja_tables.c
    ${VENDOR}/lang/jpn/port/ja_voice.c
    ${VENDOR}/lang/jpn/port/tvtts_ja.c
)

set(ES_SOURCES
    ${VENDOR}/lang/spa/engine/adjust.c
    ${VENDOR}/lang/spa/engine/bittab.c
    ${VENDOR}/lang/spa/engine/cluster.c
    ${VENDOR}/lang/spa/engine/contour.c
    ${VENDOR}/lang/spa/engine/control.c
    ${VENDOR}/lang/spa/engine/engine.c
    ${VENDOR}/lang/spa/engine/escape.c
    ${VENDOR}/lang/spa/engine/flush.c
    ${VENDOR}/lang/spa/engine/generate.c
    ${VENDOR}/lang/spa/engine/input.c
    ${VENDOR}/lang/spa/engine/interp.c
    ${VENDOR}/lang/spa/engine/list.c
    ${VENDOR}/lang/spa/engine/node.c
    ${VENDOR}/lang/spa/engine/number.c
    ${VENDOR}/lang/spa/engine/params.c
    ${VENDOR}/lang/spa/engine/phone.c
    ${VENDOR}/lang/spa/engine/preformat.c
    ${VENDOR}/lang/spa/engine/prosody.c
    ${VENDOR}/lang/spa/engine/reset.c
    ${VENDOR}/lang/spa/engine/ring.c
    ${VENDOR}/lang/spa/engine/rule.c
    ${VENDOR}/lang/spa/engine/segment.c
    ${VENDOR}/lang/spa/engine/stage.c
    ${VENDOR}/lang/spa/engine/stage0.c
    ${VENDOR}/lang/spa/engine/stage1.c
    ${VENDOR}/lang/spa/engine/stage2.c
    ${VENDOR}/lang/spa/engine/stage3.c
    ${VENDOR}/lang/spa/engine/stage3seg.c
    ${VENDOR}/lang/spa/engine/stage4.c
    ${VENDOR}/lang/spa/engine/synth.c
    ${VENDOR}/lang/spa/engine/tables.c
    ${VENDOR}/lang/spa/engine/textin.c
    ${VENDOR}/lang/spa/engine/track.c
    ${VENDOR}/lang/spa/engine/util.c
    # OpenTV's own extra Spanish voice (Francisco).
    ${VENDOR}/lang/spa/engine/voices.c
    ${VENDOR}/lang/spa/port/msvcrt_es.c
    ${VENDOR}/lang/spa/port/tvtts_es.c
    ${VENDOR}/generated/tvdata_es.s
)

# English engine + data image. Own library so the Spanish rename
# force-include below touches only Spanish translation units.
add_library(truvoice_en STATIC ${EN_SOURCES})
target_include_directories(truvoice_en PRIVATE
    ${VENDOR}/src
    ${VENDOR}/generated
    ${VENDOR}/include
    ${VENDOR}/lang/eng/data
    ${VENDOR}/lang/jpn/port
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
    ${VENDOR}/lang/spa/engine
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
# m: the Japanese pitch contour uses exp and log.
target_link_libraries(truvoicedroid truvoice_en truvoice_es log m)
