package org.truevoicedroid.tts

/** One finished utterance from the engine: mono samples at 16 kHz plus the
 * index marks the engine passed, each with the engine-sample position where
 * synthesis reached it. Constructed by native code. */
class SynthResult(
    val samples: ShortArray,
    val markIds: IntArray,
    val markPos: IntArray
)

/** JNI entry points into libtruvoicedroid.so (TruVoice engine, tables
 * embedded). A handle is one tvtts_synth for one voice at 16 kHz. */
object TruVoiceNative {
    init {
        System.loadLibrary("truvoicedroid")
    }

    @JvmStatic external fun nativeCreateLang(lang: String, sampleRateHz: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeSetVoice(handle: Long, voice: Int)
    @JvmStatic external fun nativeSetRate(handle: Long, wpm: Int)
    @JvmStatic external fun nativeSetPitch(handle: Long, pitch: Int)
    @JvmStatic external fun nativeAddLexicon(word: String, phonemes: String): Int
    @JvmStatic external fun nativeVoiceCount(): Int
    @JvmStatic external fun nativeVoiceName(voice: Int): String?
    @JvmStatic external fun nativeVoiceRate(voice: Int): Int
    @JvmStatic external fun nativeVoicePitch(voice: Int): Int
    /** The inline escape that raises an index mark, or null. */
    @JvmStatic external fun nativeMarkSequence(mark: Int): String?
    /** The inline pause escape for [ms] hundredths-of-a-second units. */
    @JvmStatic external fun nativeBreakSequence(ms: Int): String?
    /** Blocks until the utterance finishes. Null on empty text, embedded
     * NUL, engine error, or when aborted before producing audio. */
    @JvmStatic external fun nativeSynthesize(handle: Long, text: String): SynthResult?
    /** Aborts an in-progress synthesize on [handle] at once. */
    @JvmStatic external fun nativeRequestAbort(handle: Long)
}
