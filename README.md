# TrueVoiceDroid

Centigram TruVoice as an Android `TextToSpeechService` (package
`org.truevoicedroid.tts`), with Google-TTS fallback. 20 voices
(Peter–Julia en, Pedro–Isabel es), 16 kHz mono, word-boundary marks,
spinner selection saved as the TalkBack default voice, shorten-pauses
on by default.

## Tables / rights

Personal build: the engine sources and tables under
`app/src/main/cpp/vendor/` are vendored in and compiled into
`libtruvoicedroid.so`. See `NOTICE` (also shipped in assets): do NOT
redistribute this APK publicly.

Pinned upstream: the vendor tree was lifted from devinprater/iTruVoice
@ `6082178`.

## Build

Needs the Android SDK (point `local.properties` `sdk.dir` at it) and JDK 17
(`org.gradle.java.home` or `JAVA_HOME`). Gradle 8.13, AGP 8.5.2, Kotlin 1.9.24.

```
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

`app/src/main/cpp/test_synth.c` is a headless on-device smoke test
(raw s16le PCM to stdout); it is not part of the app build.
