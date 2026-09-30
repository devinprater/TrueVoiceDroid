package org.truevoicedroid.tts

import android.content.Context
import android.util.Log

/**
 * Owns the native TruVoice synths: one tvtts_synth per voice index, each
 * opened at 16 kHz in its own language ("en" for 0-9, "es" for 10-19).
 *
 * Ported from iTruVoice TruVoice: a synth is not thread safe, so every
 * method here serializes on one lock and the service additionally funnels
 * all requests through a single synthesis executor. The pronunciation
 * fixes (assets/lexicon.tsv: hand-measured entries plus the
 * machine-verified stress table) install once per process — the engine's
 * user lexicon is a single process-global table, shared across languages
 * the same way: the keys are English words read with English phonemes.
 */
class TruVoiceEngine(private val appContext: Context) {

    data class Mark(val id: Int, val samplePos: Int)

    data class Utterance(val samples: ShortArray, val marks: List<Mark>)

    /**
     * Engine-ready text plus the word spans each mark belongs to. Marks go
     * inline *before* the word they belong to — a mark after the last word
     * changes how the engine reads that word (a lone "a" becomes the
     * article rather than the letter's name), so trailing marks are never
     * embedded. Spans are offsets into [plain] (escapes excluded), which is
     * what rangeStart reports.
     */
    data class EngineText(
        val engineText: String,
        val plain: String,
        val words: List<WordSpan>
    )

    data class WordSpan(val markId: Int, val start: Int, val end: Int)

    private val lock = Any()
    private val handles = mutableMapOf<Int, Long>()
    private var lexiconReady = false
    private var dictionary: List<Pair<String, String>>? = null

    /**
     * Output rate in Hz (16000 or 11025). New synths open at it; live ones
     * switch between utterances via tvtts_set_sample_rate, which preserves
     * voice/pitch/rate/volume. Guarded by [lock] with the handles.
     */
    private var sampleRateHz = VoiceCatalog.SAMPLE_RATE_HZ

    /** Current output rate. */
    fun sampleRateHz(): Int = synchronized(lock) { sampleRateHz }

    /**
     * Switches every live synth to [hz] (16000 or 11025) and makes it the
     * rate for new synths. Call between utterances; a synth caught
     * mid-utterance keeps its rate (the set call is refused) and is
     * recreated on next use if it disagrees. Returns the rate in effect.
     */
    fun setSampleRateHz(hz: Int): Int {
        val which = when (hz) {
            11025 -> TruVoiceNative.SR_11K
            else -> TruVoiceNative.SR_16K
        }
        val target = if (which == TruVoiceNative.SR_11K) 11025 else 16000
        synchronized(lock) {
            sampleRateHz = target
            val dead = mutableListOf<Int>()
            for ((voice, handle) in handles) {
                val rc = try {
                    TruVoiceNative.nativeSetSampleRate(handle, which)
                } catch (e: Exception) {
                    Log.w(TAG, "set rate failed for voice $voice", e)
                    -1
                }
                if (rc != 0) dead.add(voice)
            }
            // A refused switch (mid-utterance) leaves a stale-rate synth;
            // abort and drop it so the next request opens a fresh one at
            // the target — same abort-then-destroy as discardVoice.
            for (voice in dead) {
                handles.remove(voice)?.let {
                    try {
                        TruVoiceNative.nativeRequestAbort(it)
                    } catch (t: Throwable) {
                        Log.w(TAG, "abort after refused rate switch failed", t)
                    }
                    try {
                        TruVoiceNative.nativeDestroy(it)
                    } catch (t: Throwable) {
                        Log.w(TAG, "destroy after refused rate switch failed", t)
                    }
                }
            }
            return target
        }
    }

    fun dictionary(): List<Pair<String, String>> {
        synchronized(lock) {
            dictionary?.let { return it }
            val loaded = mutableListOf<Pair<String, String>>()
            try {
                appContext.assets.open("dictionary.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            loaded.add(
                                line.substring(0, tab) to line.substring(tab + 1)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "dictionary.tsv missing, continuing without it", e)
            }
            dictionary = loaded
            return loaded
        }
    }

    private fun ensureLexicon() {
        synchronized(lock) {
            if (lexiconReady) return
            try {
                appContext.assets.open("lexicon.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            try {
                                TruVoiceNative.nativeAddLexicon(
                                    line.substring(0, tab),
                                    line.substring(tab + 1)
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "lexicon entry failed", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "lexicon.tsv missing, continuing without fixes", e)
            }
            lexiconReady = true
        }
    }

    /** The voice's own default rate in words per minute. */
    fun defaultRateWpm(voice: Int): Int = try {
        TruVoiceNative.nativeVoiceRate(voice).takeIf { it > 0 } ?: 150
    } catch (e: Exception) {
        150
    }

    /** The voice's own default absolute pitch. */
    fun defaultPitch(voice: Int): Int = try {
        TruVoiceNative.nativeVoicePitch(voice).takeIf { it > 0 } ?: 85
    } catch (e: Exception) {
        85
    }

    private fun handleFor(voice: Int): Long {
        synchronized(lock) {
            ensureLexicon()
            handles[voice]?.let { return it }
            val lang = VoiceCatalog.languageFor(voice)
            val handle = TruVoiceNative.nativeCreateLang(
                lang, sampleRateHz
            )
            if (handle == 0L) throw IllegalStateException("create_lang($lang) failed")
            TruVoiceNative.nativeSetVoice(handle, voice)
            handles[voice] = handle
            return handle
        }
    }

    /**
     * Builds engine text for [plain]: one index mark before every word.
     * Mark ids are 1-based word positions; ids stay small (the escape
     * carries a uint32, but small ids keep the text compact).
     *
     * The engines' mark argument is a single byte, so ids only survive
     * 1-255. [buildEngineTexts] splits longer input into chunks that fit.
     */
    fun buildEngineText(plain: String): EngineText =
        buildEngineTexts(plain, Int.MAX_VALUE).single()

    /** [plain] split into engine texts of at most [maxWords] words each. */
    fun buildEngineTexts(plain: String, maxWords: Int = 200): List<EngineText> {
        val words = plain.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(EngineText("", "", emptyList()))
        return words.chunked(maxWords.coerceAtLeast(1)).map { chunk ->
            buildChunk(chunk)
        }
    }

    private fun buildChunk(words: List<String>): EngineText {
        val engine = StringBuilder(words.sumOf { it.length } + words.size * 8)
        val plainBuilder = StringBuilder(engine.capacity())
        val spans = mutableListOf<WordSpan>()
        words.forEachIndexed { i, word ->
            val id = i + 1
            val escape = try {
                TruVoiceNative.nativeMarkSequence(id)
            } catch (e: Exception) {
                null
            }
            if (plainBuilder.isNotEmpty()) {
                engine.append(' ')
                plainBuilder.append(' ')
            }
            if (escape != null) engine.append(escape)
            val start = plainBuilder.length
            engine.append(word)
            plainBuilder.append(word)
            spans.add(WordSpan(id, start, start + word.length))
        }
        return EngineText(engine.toString(), plainBuilder.toString(), spans)
    }

    /**
     * Synthesizes [engineText] at the current rate/pitch settings.
     * Returns null for empty text or engine error. Honors abort requests
     * from [requestAbort].
     */
    fun synthesize(
        voice: Int,
        wpm: Int,
        pitch: Int,
        engineText: String
    ): Utterance? {
        val handle: Long
        synchronized(lock) {
            handle = try {
                handleFor(voice)
            } catch (e: Exception) {
                Log.e(TAG, "no synth for voice $voice", e)
                return null
            }
            try {
                TruVoiceNative.nativeSetRate(handle, wpm)
                TruVoiceNative.nativeSetPitch(handle, pitch)
            } catch (e: Exception) {
                Log.e(TAG, "engine settings failed", e)
                return null
            }
        }
        // The blocking speak runs outside the lock so abort/discard can
        // intervene; the service serializes requests, so no two threads
        // share this synth.
        val result = try {
            TruVoiceNative.nativeSynthesize(handle, engineText)
        } catch (e: Exception) {
            Log.e(TAG, "synthesize failed", e)
            null
        } ?: return null
        return Utterance(
            result.samples,
            result.markIds.indices.map { Mark(result.markIds[it], result.markPos[it]) }
        )
    }

    /** True when the engine produces non-silent audio for [text]. */
    fun producesSpeech(utterance: Utterance?): Boolean {
        if (utterance == null || utterance.samples.isEmpty()) return false
        return utterance.samples.any { it != 0.toShort() }
    }

    fun requestAbort(voice: Int) {
        synchronized(lock) {
            handles[voice]?.let {
                try {
                    TruVoiceNative.nativeRequestAbort(it)
                } catch (e: Exception) {
                    Log.w(TAG, "abort failed", e)
                }
            }
        }
    }

    /**
     * Drops the synth for [voice]. Used after a timeout or abort, when the
     * blocked native call may still hold the old synth: the next request
     * opens a fresh one instead of reusing a wedged handle.
     */
    fun discardVoice(voice: Int) {
        synchronized(lock) {
            handles.remove(voice)?.let {
                try {
                    TruVoiceNative.nativeRequestAbort(it)
                } catch (e: Exception) {
                    Log.w(TAG, "abort on discard failed", e)
                }
                try {
                    TruVoiceNative.nativeDestroy(it)
                } catch (e: Exception) {
                    Log.w(TAG, "destroy on discard failed", e)
                }
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            handles.values.forEach {
                try {
                    TruVoiceNative.nativeDestroy(it)
                } catch (e: Exception) {
                    Log.w(TAG, "destroy failed", e)
                }
            }
            handles.clear()
        }
    }

    companion object {
        private const val TAG = "TruVoiceEngine"
    }
}
