package org.truevoicedroid.tts

/** One finished utterance from the engine: mono samples at the synth's
 * current output rate plus the index marks the engine passed, each with
 * the engine-sample position where synthesis reached it. Constructed by
 * native code. */
class SynthResult(
    val samples: ShortArray,
    val markIds: IntArray,
    val markPos: IntArray
)

/** JNI entry points into libtruvoicedroid.so (TruVoice engine, tables
 * embedded). A handle is one tvtts_synth for one voice, created at a
 * given output rate and switchable between utterances. */
object TruVoiceNative {
    init {
        System.loadLibrary("truvoicedroid")
    }

    /** TVTTS_SR_* index for 11025 Hz: the classic desktop TruVoice sound. */
    const val SR_11K = 1

    /** TVTTS_SR_* index for 16000 Hz: wider bandwidth, same voices. */
    const val SR_16K = 2

    @JvmStatic external fun nativeCreateLang(lang: String, sampleRateHz: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeSetVoice(handle: Long, voice: Int)
    @JvmStatic external fun nativeSetRate(handle: Long, wpm: Int)
    @JvmStatic external fun nativeSetPitch(handle: Long, pitch: Int)
    /** Switches output rate between utterances; 0 ok, -1 refused/failed. */
    @JvmStatic external fun nativeSetSampleRate(handle: Long, which: Int): Int
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
