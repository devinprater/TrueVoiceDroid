# TrueVoiceDroid

Centigram TruVoice as an Android `TextToSpeechService` (package
`org.truevoicedroid.tts`), with Google-TTS fallback. 33 voices
(Peter–Frank en-US, Pedro–Francisco es-ES, Nigel–Bernard en-GB), 16 kHz
mono, word-boundary marks, spinner selection saved as the TalkBack default
voice, shorten-pauses on by default.

British English is the same engine with stage 3's vowel targets re-aimed at
General British and the accent's phoneme substitutions applied, not a second
set of recordings: each British voice is the American speaker of the same
number minus 32 under a British name. The engine also carries Japanese
(indices 22–31) and it is not offered: its front end needs the 27 MB
`jadic.bin` upstream loads from disk at runtime, which this app does not ship.

## Tables / rights

Personal build: the engine sources and tables under
`app/src/main/cpp/vendor/` are vendored in and compiled into
`libtruvoicedroid.so`. See `NOTICE` (also shipped in assets): do NOT
redistribute this APK publicly.

Pinned upstream: tv-decomp `8488e51` (upstream keeps each language in its own
tree: `lang/enu`, `lang/spa`, `lang/jpn`, `lang/eng`). The vendor tree is
re-synced and its table images rebuilt with

```
TVDECOMP=/path/to/tv-decomp bash tools/regen_generated.sh
```

Run that deliberately when the pin moves, then commit the result; Gradle never
runs it.

## Build

Needs the Android SDK (point `local.properties` `sdk.dir` at it) and JDK 17
(`org.gradle.java.home` or `JAVA_HOME`). Gradle 8.13, AGP 8.5.2, Kotlin 1.9.24.

```
./gradlew assembleDebug
```

The wrapper (Gradle 8.13) is committed, so no separate Gradle install is
needed.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

`app/src/main/cpp/test_synth.c` is a headless on-device smoke test
(raw s16le PCM to stdout); it is not part of the app build.
