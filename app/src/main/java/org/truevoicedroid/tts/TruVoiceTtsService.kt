package org.truevoicedroid.tts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * TruVoice as an Android TTS engine: the twenty Centigram voices (ten
 * English, ten Spanish) at 16 kHz mono, with a Google-TTS fallback.
 *
 * Request flow: pick the voice (request voice name, else request
 * language, else Peter), resolve the text through TextProcessor (SSML and
 * plain text alike), synthesize each speech piece through the native
 * engine inside an executor with a proportional timeout, and stream the
 * PCM with audioAvailable. Word-boundary timing comes from the engine's
 * own index marks via rangeStart. Pauses render as silence so stop stays
 * responsive.
 *
 * When native synthesis times out, throws, or returns all silence, the
 * request falls back to the configured Google-TTS package
 * (synthesizeToFile, read back as 16 kHz mono) when fallback is enabled —
 * the same contract as the sibling port. The service never crashes:
 * every entry point catches Throwable and ends the request with
 * callback.error() as a last resort.
 */
class TruVoiceTtsService : TextToSpeechService() {

    private lateinit var engine: TruVoiceEngine
    private lateinit var synthExecutor: ExecutorService

    @Volatile
    private var stopped = false

    @Volatile
    private var activeVoice = VoiceCatalog.DEFAULT_VOICE

    @Volatile
    private var activeFuture: Future<*>? = null

    @Volatile
    private var currentLang = "en"

    @Volatile
    private var currentCountry = "US"

    override fun onCreate() {
        super.onCreate()
        engine = TruVoiceEngine(applicationContext)
        synthExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onDestroy() {
        try {
            synthExecutor.shutdownNow()
        } catch (t: Throwable) {
            Log.w(TAG, "executor shutdown failed", t)
        }
        try {
            engine.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "engine shutdown failed", t)
        }
        super.onDestroy()
    }

    // MARK: - Languages and voices

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return availabilityFor(lang, country)
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = availabilityFor(lang, country)
        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            currentLang = lang?.lowercase() ?: "en"
            currentCountry = country?.uppercase()
                ?: if (currentLang == "es") "ES" else "US"
        }
        return availability
    }

    private fun availabilityFor(lang: String?, country: String?): Int {
        return when (lang?.lowercase()) {
            "en", "eng" -> if (country.isNullOrEmpty() || country.equals("US", ignoreCase = true)) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
            "es", "spa" -> if (country.isNullOrEmpty() || country.equals("ES", ignoreCase = true)) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf(currentLang, currentCountry, "")

    override fun onGetVoices(): MutableList<Voice> {
        return VoiceCatalog.all.map { info ->
            Voice(
                VoiceCatalog.androidNameFor(info.index),
                Locale.forLanguageTag(info.language),
                Voice.QUALITY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
            )
        }.toMutableList()
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        return if (VoiceCatalog.indexFromAndroidName(voiceName) != null) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onLoadVoice(voiceName: String?): Int {
        val index = VoiceCatalog.indexFromAndroidName(voiceName) ?: return TextToSpeech.ERROR
        prefs().edit().putInt(KEY_DEFAULT_VOICE, index).apply()
        return TextToSpeech.SUCCESS
    }

    /**
     * The voice TalkBack and the system voice picker use when the client
     * only sets a language: the user's saved voice when it speaks that
     * language, else Peter/Pedro.
     */
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val saved = prefs().getInt(KEY_DEFAULT_VOICE, VoiceCatalog.DEFAULT_VOICE)
        val wantEs = lang?.lowercase()?.startsWith("es") == true
        val savedIsEs = saved in 10..19
        if (saved in 0..19 && savedIsEs == wantEs) {
            return VoiceCatalog.androidNameFor(saved)
        }
        return VoiceCatalog.androidNameFor(if (wantEs) 10 else 0)
    }

    // MARK: - Synthesis

    override fun onStop() {
        stopped = true
        try {
            activeFuture?.cancel(true)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel failed", t)
        }
        try {
            engine.requestAbort(activeVoice)
        } catch (t: Throwable) {
            Log.w(TAG, "abort failed", t)
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopped = false
        try {
            synthesize(request, callback)
        } catch (t: Throwable) {
            Log.e(TAG, "synthesis failed", t)
            finishWithError(callback)
        }
    }

    private fun synthesize(request: SynthesisRequest, callback: SynthesisCallback) {
        val voice = pickVoice(request)
        activeVoice = voice
        val info = VoiceCatalog.infoFor(voice)
            ?: VoiceCatalog.infoFor(VoiceCatalog.DEFAULT_VOICE)!!
        val ratePercent = try {
            request.speechRate
        } catch (t: Throwable) {
            100
        }
        val pitchPercent = try {
            request.pitch
        } catch (t: Throwable) {
            100
        }
        val defaultWpm = engine.defaultRateWpm(voice)
        val defaultPitch = engine.defaultPitch(voice)
        val baseWpm = VoiceParams.rateForAndroid(ratePercent, defaultWpm)
        val basePitch = VoiceParams.pitchForAndroid(pitchPercent, defaultPitch)

        val input: String = try {
            request.text
        } catch (t: Throwable) {
            ""
        }
        if (input.isBlank()) {
            finishWithError(callback)
            return
        }
        val parsed = try {
            TextProcessor.parse(input, engine.dictionary())
        } catch (t: Throwable) {
            Log.e(TAG, "text processing failed", t)
            finishWithError(callback)
            return
        }
        val speechPieces = parsed.pieces.filterIsInstance<TextProcessor.Piece.Speech>()
        val hasAudio = speechPieces.any { it.text.isNotBlank() } ||
            parsed.pieces.any { it is TextProcessor.Piece.Pause && it.seconds > 0 }
        if (!hasAudio) {
            finishWithError(callback)
            return
        }

        val streamer = Streamer(callback)
        var globalOffset = 0 // char offset into the joined spoken text

        for (piece in parsed.pieces) {
            if (stopped) return // onStop owns the request now
            when (piece) {
                is TextProcessor.Piece.Pause -> {
                    streamer.writeSilence(piece.seconds)
                    globalOffset += 1
                }
                is TextProcessor.Piece.Bookmark -> {
                    // No bookmark channel in the TTS callback; timing comes
                    // from word marks instead.
                }
                is TextProcessor.Piece.Speech -> {
                    if (piece.text.isBlank()) continue
                    val wpm = piece.rate?.let {
                        VoiceParams.rateForVoiceOver(it, defaultWpm)
                    } ?: baseWpm
                    val pitch = piece.pitch?.let {
                        VoiceParams.pitchForVoiceOver(it, defaultPitch)
                    } ?: basePitch
                    val gain = VoiceParams.BASE_BOOST *
                        VoiceParams.gainForVolume(piece.volume)
                    // The engines' mark argument is one byte, so a piece
                    // longer than 255 words is synthesized in chunks that
                    // keep every word's mark.
                    val chunks = try {
                        engine.buildEngineTexts(piece.text)
                    } catch (t: Throwable) {
                        Log.e(TAG, "mark setup failed", t)
                        continue
                    }
                    var chunkOffset = 0
                    var pieceSilent = true
                    for (chunk in chunks) {
                        if (stopped) break
                        if (chunk.engineText.isBlank()) continue
                        val utterance = synthesizeNative(
                            voice, wpm, pitch, chunk.engineText
                        )
                        if (!engine.producesSpeech(utterance)) {
                            engine.discardVoice(voice)
                            continue
                        }
                        pieceSilent = false
                        streamer.writeUtterance(
                            utterance!!,
                            chunk,
                            globalOffset + chunkOffset,
                            gain,
                            streamer.framesOut
                        )
                        chunkOffset += chunk.plain.length + 1
                    }
                    if (stopped) break
                    if (pieceSilent) {
                        // Piece-level failure: skip it; the request-level
                        // fallback below covers a fully silent request.
                        continue
                    }
                    globalOffset += chunkOffset
                }
            }
        }

        if (stopped) return
        if (!streamer.started) {
            // Native synthesis produced nothing usable: fall back to the
            // configured Google-TTS package for the whole request.
            if (!speakViaFallback(request, info, ratePercent, pitchPercent, streamer)) {
                finishWithError(callback)
                return
            }
        }
        if (stopped) return
        try {
            callback.done()
            Log.i(TAG, "request done voice=$voice rate=$ratePercent pitch=$pitchPercent frames=${streamer.framesOut}")
        } catch (t: Throwable) {
            Log.w(TAG, "done() failed", t)
        }
    }

    private fun pickVoice(request: SynthesisRequest): Int {
        val voiceOverride = try {
            prefs().getBoolean(KEY_OVERRIDE_VOICE, true)
        } catch (t: Throwable) {
            true
        }
        // Panthera's override_voice: the settings voice wins over every
        // request, so a TalkBack user gets the voice they picked. It still
        // has to speak the requested language.
        if (!voiceOverride) {
            try {
                VoiceCatalog.indexFromAndroidName(request.voiceName)?.let { return it }
            } catch (t: Throwable) {
                Log.w(TAG, "voice name lookup failed", t)
            }
        }
        // The user's saved voice (TalkBack only sets a language), when it
        // speaks the requested language; else Peter/Pedro by language.
        val wantEs = request.language?.lowercase()?.startsWith("es") == true ||
            (request.language.isNullOrEmpty() && currentLang == "es")
        val saved = try {
            prefs().getInt(KEY_DEFAULT_VOICE, VoiceCatalog.DEFAULT_VOICE)
        } catch (t: Throwable) {
            VoiceCatalog.DEFAULT_VOICE
        }
        if (saved in 0..19 && (saved in 10..19) == wantEs) return saved
        return try {
            when (request.language?.lowercase()) {
                "es", "spa" -> 10 // Pedro
                "en", "eng" -> 0 // Peter
                else -> if (currentLang == "es") 10 else 0
            }
        } catch (t: Throwable) {
            VoiceCatalog.DEFAULT_VOICE
        }
    }

    /**
     * Runs one native utterance inside the synthesis executor with the
     * proportional timeout. Null on timeout, interruption, or engine
     * error; the handle is discarded on timeout so a wedged synth is never
     * reused.
     */
    private fun synthesizeNative(
        voice: Int,
        wpm: Int,
        pitch: Int,
        engineText: String
    ): TruVoiceEngine.Utterance? {
        val timeoutMs = GoogleTtsFallback.nativeTimeoutMs(engineText.length)
        val future = synthExecutor.submit<TruVoiceEngine.Utterance?> {
            engine.synthesize(voice, wpm, pitch, engineText)
        }
        activeFuture = future
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "native synth timed out, discarding voice $voice")
            try {
                future.cancel(true)
            } catch (t: Throwable) {
                Log.w(TAG, "cancel after timeout failed", t)
            }
            engine.discardVoice(voice)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (t: Throwable) {
            Log.w(TAG, "native synth failed", t)
            null
        } finally {
            activeFuture = null
        }
    }

    private fun speakViaFallback(
        request: SynthesisRequest,
        info: VoiceCatalog.VoiceInfo,
        ratePercent: Int,
        pitchPercent: Int,
        streamer: Streamer
    ): Boolean {
        if (!GoogleTtsFallback.isEnabled(this)) return false
        val text = try {
            TextProcessor.plainText(request.text, engine.dictionary())
        } catch (t: Throwable) {
            Log.w(TAG, "fallback text prep failed", t)
            return false
        }
        if (text.isBlank()) return false
        // The fallback render blocks; run it on the synthesis executor so
        // stop can still intervene via the stopped flag.
        val timeoutMs = GoogleTtsFallback.nativeTimeoutMs(text.length) + 15000
        val future = synthExecutor.submit<GoogleTtsFallback.FallbackAudio?> {
            GoogleTtsFallback.synthesizeBlocking(
                this,
                text,
                Locale.forLanguageTag(info.language),
                ratePercent / 100.0f,
                pitchPercent / 100.0f,
                timeoutMs
            )
        }
        activeFuture = future
        return try {
            val audio = future.get(timeoutMs + 10000, TimeUnit.MILLISECONDS)
            activeFuture = null
            if (audio == null || audio.samples.isEmpty()) return false
            if (stopped) return false
            streamer.writeSamples(audio.samples, emptyList(), 0, 1.0f)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "fallback render failed", t)
            try {
                future.cancel(true)
            } catch (ignored: Throwable) {
            }
            activeFuture = null
            false
        }
    }

    private fun finishWithError(callback: SynthesisCallback) {
        try {
            callback.error()
        } catch (t: Throwable) {
            Log.w(TAG, "error() failed", t)
        }
    }

    /**
     * Streams 16 kHz mono PCM into the synthesis callback, with gain and
     * word-boundary timing. Starts the callback lazily on the first audio
     * so a request that ends up fully silent never opens the stream.
     */
    private inner class Streamer(private val callback: SynthesisCallback) {
        var started = false
            private set

        /** Audio frames streamed so far: the base for chunk mark offsets. */
        var framesOut = 0
            private set

        fun writeUtterance(
            utterance: TruVoiceEngine.Utterance,
            engineText: TruVoiceEngine.EngineText,
            globalOffset: Int,
            gain: Float,
            baseFrame: Int = 0
        ) {
            if (stopped) return
            val byId = engineText.words.associateBy { it.markId }
            val marks = utterance.marks
                .filter { byId.containsKey(it.id) }
                .sortedBy { it.samplePos }
            val timed = marks.map {
                val span = byId.getValue(it.id)
                TimedMark(it.samplePos, globalOffset + span.start, globalOffset + span.end)
            }
            if (shortenPauses()) {
                val (shortSamples, shortMarks) = shortenWithMarks(utterance.samples, timed)
                val dropped = utterance.samples.size - shortSamples.size
                if (dropped > 0) {
                    Log.i(TAG, "shortened pauses: dropped $dropped samples")
                }
                writeSamples(shortSamples, shortMarks, baseFrame, gain)
            } else {
                writeSamples(utterance.samples, timed, baseFrame, gain)
            }
        }

        private fun shortenPauses(): Boolean = try {
            prefs().getBoolean(KEY_SHORTEN_PAUSES, true)
        } catch (t: Throwable) {
            true
        }

        /**
         * Shortens pauses, remapping word-mark frames onto the shortened
         * output so rangeStart timing follows the audio that is played.
         */
        private fun shortenWithMarks(
            samples: ShortArray,
            marks: List<TimedMark>
        ): Pair<ShortArray, List<TimedMark>> {
            val shortener = PauseShortener(VoiceCatalog.SAMPLE_RATE_HZ)
            val sorted = marks.sortedBy { it.frame }
            val out = ShortArray(samples.size)
            val adj = ArrayList<TimedMark>(sorted.size)
            var mi = 0
            var w = 0
            try {
                for (i in samples.indices) {
                    if (stopped) break
                    while (mi < sorted.size && sorted[mi].frame <= i) {
                        val m = sorted[mi]
                        adj.add(TimedMark(w, m.start, m.end))
                        mi++
                    }
                    val emit = shortener.feed(samples[i])
                    if (emit != null) out[w++] = emit
                }
                while (mi < sorted.size) {
                    val m = sorted[mi]
                    adj.add(TimedMark(w, m.start, m.end))
                    mi++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "shorten failed", t)
                return Pair(samples, marks)
            }
            return Pair(if (w == samples.size) samples else out.copyOf(w), adj)
        }

        fun writeSamples(
            samples: ShortArray,
            marks: List<TimedMark>,
            baseFrame: Int,
            gain: Float
        ) {
            if (samples.isEmpty() || stopped) return
            ensureStarted()
            if (!started || stopped) return
            val bytes = leBytes(samples, gain)
            var byteOff = 0
            var markIdx = 0
            // Emit each word mark when the play cursor passes it, so timing
            // follows the audio even in chunks.
            val chunkSamples = 2048
            while (byteOff < bytes.size && !stopped) {
                val end = minOf(byteOff + chunkSamples * 2, bytes.size)
                try {
                    callback.audioAvailable(bytes, byteOff, end - byteOff)
                } catch (t: Throwable) {
                    Log.w(TAG, "audioAvailable failed", t)
                    return
                }
                val framesNow = (end - byteOff) / 2
                val cursor = baseFrame + framesOut + framesNow
                while (markIdx < marks.size && baseFrame + marks[markIdx].frame <= cursor) {
                    val m = marks[markIdx]
                    try {
                        callback.rangeStart(baseFrame + m.frame, m.start, m.end)
                    } catch (t: Throwable) {
                        Log.w(TAG, "rangeStart failed", t)
                    }
                    markIdx++
                }
                framesOut += framesNow
                byteOff = end
            }
            // Marks past the final cursor (the engine reports trailing
            // positions) are reported now that the audio exists.
            while (markIdx < marks.size && !stopped) {
                val m = marks[markIdx]
                try {
                    callback.rangeStart(baseFrame + m.frame, m.start, m.end)
                } catch (t: Throwable) {
                    Log.w(TAG, "rangeStart failed", t)
                }
                markIdx++
            }
        }

        fun writeSilence(seconds: Double) {
            if (seconds <= 0 || stopped) return
            // Shortened pauses cap inter-piece silence too.
            val capped = if (shortenPauses()) minOf(seconds, 0.25) else seconds
            if (capped <= 0) return
            ensureStarted()
            if (!started) return
            var remaining = (capped * VoiceCatalog.SAMPLE_RATE_HZ).toInt()
            val chunk = ByteArray(2048 * 2)
            while (remaining > 0 && !stopped) {
                val n = minOf(remaining, 2048)
                try {
                    callback.audioAvailable(chunk, 0, n * 2)
                } catch (t: Throwable) {
                    Log.w(TAG, "silence write failed", t)
                    return
                }
                framesOut += n
                remaining -= n
            }
        }

        private fun ensureStarted() {
            if (started) return
            try {
                callback.start(
                    VoiceCatalog.SAMPLE_RATE_HZ,
                    AudioFormat.ENCODING_PCM_16BIT,
                    1
                )
                started = true
            } catch (t: Throwable) {
                Log.w(TAG, "callback.start failed", t)
            }
        }

        private fun leBytes(samples: ShortArray, gain: Float): ByteArray {
            val out = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                var v = (samples[i] * gain).toInt()
                if (v > 32767) v = 32767
                if (v < -32768) v = -32768
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
            }
            return out
        }
    }

    private data class TimedMark(val frame: Int, val start: Int, val end: Int)

    companion object {
        private const val TAG = "TruVoiceTtsService"
        const val PREFS = "truvoice"
        const val KEY_DEFAULT_VOICE = "default_voice"

        /** Settings voice wins over every request (Panthera's override_voice). */
        const val KEY_OVERRIDE_VOICE = "override_voice"

        /** Cap silent runs at ~200 ms (Panthera's "fewest pauses"). */
        const val KEY_SHORTEN_PAUSES = "shorten_pauses"
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
