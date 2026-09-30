package org.truevoicedroid.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Google-TTS fallback with the same contract as the sibling engine port:
 *
 * - Preferences live in "truvoice_fallback": "enabled" (default ON),
 *   "package" (default "com.google.android.tts"), honored by both the
 *   service and the app UI.
 * - The service wraps native synthesis in an executor with a proportional
 *   timeout (a base plus a per-character allowance, capped). On timeout,
 *   exception, or all-silence output it falls back instead of failing.
 * - The fallback speaks through TextToSpeech bound to the fallback
 *   package, renders with synthesizeToFile into the cache dir, reads the
 *   WAV back, and hands 16 kHz mono PCM to the same streaming path.
 * - Nothing here ever crashes the service: every entry point catches
 *   Throwable and reports failure as null/false, leaving the caller to
 *   end the request with callback.error().
 */
object GoogleTtsFallback {
    const val PREFS = "truvoice_fallback"
    const val KEY_ENABLED = "enabled"
    const val KEY_PACKAGE = "package"
    const val DEFAULT_PACKAGE = "com.google.android.tts"

    const val SAMPLE_RATE_HZ = 16000

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun fallbackPackage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PACKAGE, DEFAULT_PACKAGE) ?: DEFAULT_PACKAGE

    fun setFallbackPackage(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PACKAGE, packageName.ifBlank { DEFAULT_PACKAGE }).apply()
    }

    /**
     * Proportional native-synth timeout: a base allowance for engine
     * startup plus a per-character allowance for the utterance itself,
     * capped so a pathological request cannot stall the queue.
     */
    fun nativeTimeoutMs(textLength: Int): Long =
        (BASE_TIMEOUT_MS + textLength * PER_CHAR_TIMEOUT_MS)
            .coerceAtMost(MAX_TIMEOUT_MS)

    private const val BASE_TIMEOUT_MS = 4000L
    private const val PER_CHAR_TIMEOUT_MS = 25L
    private const val MAX_TIMEOUT_MS = 45000L

    data class FallbackAudio(val samples: ShortArray)

    /**
     * Renders [text] with the fallback engine and returns 16 kHz mono PCM,
     * or null when the fallback is unavailable, fails, or times out after
     * [timeoutMs]. Must not run on the main thread (blocks); creates the
     * TextToSpeech client on the main looper internally.
     */
    fun synthesizeBlocking(
        context: Context,
        text: String,
        locale: Locale,
        rate: Float,
        pitch: Float,
        timeoutMs: Long
    ): FallbackAudio? {
        try {
            val packageName = fallbackPackage(context)
            val appContext = context.applicationContext
            val ready = CountDownLatch(1)
            val ttsRef = AtomicReference<TextToSpeech?>()
            val ok = AtomicBoolean(false)
            Handler(Looper.getMainLooper()).post {
                try {
                    ttsRef.set(
                        TextToSpeech(appContext, { status ->
                            if (status == TextToSpeech.SUCCESS) ok.set(true)
                            ready.countDown()
                        }, packageName)
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "fallback engine unavailable: $packageName", t)
                    ready.countDown()
                }
            }
            if (!ready.await(8, TimeUnit.SECONDS)) {
                shutdownOnMain(ttsRef.get())
                return null
            }
            val tts = ttsRef.get()
            if (!ok.get() || tts == null) {
                shutdownOnMain(tts)
                return null
            }
            try {
                val langStatus = tts.setLanguage(locale)
                if (langStatus == TextToSpeech.LANG_MISSING_DATA ||
                    langStatus == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    return null
                }
                tts.setSpeechRate(rate.coerceIn(0.25f, 4.0f))
                tts.setPitch(pitch.coerceIn(0.25f, 4.0f))

                val outFile = File.createTempFile("truvoice_fb", ".wav", appContext.cacheDir)
                try {
                    val utteranceId = "truvoice-fb-${System.nanoTime()}"
                    val done = CountDownLatch(1)
                    val success = AtomicBoolean(false)
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) {}
                        override fun onDone(utteranceId: String) {
                            success.set(true)
                            done.countDown()
                        }

                        @Deprecated("deprecated")
                        override fun onError(utteranceId: String) {
                            done.countDown()
                        }

                        override fun onError(utteranceId: String, errorCode: Int) {
                            done.countDown()
                        }
                    })
                    val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
                    val synthStatus = tts.synthesizeToFile(text, params, outFile, utteranceId)
                    if (synthStatus != TextToSpeech.SUCCESS) return null
                    if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
                    if (!success.get()) return null
                    val samples = readWavAs16kMono(outFile) ?: return null
                    if (samples.isEmpty() || samples.all { it == 0.toShort() }) return null
                    return FallbackAudio(samples)
                } finally {
                    runCatching { outFile.delete() }
                }
            } finally {
                shutdownOnMain(tts)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fallback synthesis failed", t)
            return null
        }
    }

    private fun shutdownOnMain(tts: TextToSpeech?) {
        if (tts == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { tts.shutdown() }
        } else {
            Handler(Looper.getMainLooper()).post { runCatching { tts.shutdown() } }
        }
    }

    /**
     * Reads a PCM WAV file (16-bit or 8-bit, any rate/channel count) and
     * returns 16 kHz mono samples via linear resampling. Null on any parse
     * failure.
     */
    fun readWavAs16kMono(file: File): ShortArray? {
        try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null
            fun u16(off: Int) =
                (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

            fun u32(off: Int) =
                (u16(off)) or (u16(off + 2) shl 16)

            if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
                bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) return null
            // Walk chunks: "fmt " then "data" (fact/LIST chunks skipped).
            var off = 12
            var channels = 0
            var rate = 0
            var bits = 0
            var format = 0
            var dataOff = -1
            var dataLen = 0
            while (off + 8 <= bytes.size) {
                val id = String(bytes, off, 4, Charsets.US_ASCII)
                val size = u32(off + 4)
                if (id == "fmt ") {
                    if (off + 8 + 16 > bytes.size) return null
                    format = u16(off + 8)
                    channels = u16(off + 10)
                    rate = u32(off + 12)
                    bits = u16(off + 22)
                } else if (id == "data") {
                    dataOff = off + 8
                    dataLen = size
                    break
                }
                off += 8 + size + (size and 1)
            }
            if (dataOff < 0 || channels <= 0 || rate <= 0) return null
            if (format != 1 && format != 3) return null
            val bytesPerSample = bits / 8
            if (bytesPerSample != 1 && bytesPerSample != 2) return null
            val frames = minOf(dataLen / (bytesPerSample * channels), (bytes.size - dataOff) / (bytesPerSample * channels))
            if (frames <= 0) return null
            val mono = FloatArray(frames)
            for (f in 0 until frames) {
                var acc = 0.0f
                for (c in 0 until channels) {
                    val pos = dataOff + (f * channels + c) * bytesPerSample
                    acc += if (bytesPerSample == 2) {
                        val v = u16(pos)
                        (if (v >= 32768) v - 65536 else v) / 32768.0f
                    } else {
                        ((bytes[pos].toInt() and 0xFF) - 128) / 128.0f
                    }
                }
                mono[f] = acc / channels
            }
            // Linear resample to 16 kHz.
            val outLen = ((frames.toLong() * SAMPLE_RATE_HZ) / rate).toInt()
            if (outLen <= 0) return null
            val out = ShortArray(outLen)
            for (i in 0 until outLen) {
                val src = i.toDouble() * rate / SAMPLE_RATE_HZ
                val i0 = src.toInt().coerceIn(0, frames - 1)
                val i1 = (i0 + 1).coerceIn(0, frames - 1)
                val frac = (src - i0).toFloat()
                val v = mono[i0] * (1 - frac) + mono[i1] * frac
                out[i] = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }
            return out
        } catch (t: Throwable) {
            Log.w(TAG, "wav read failed", t)
            return null
        }
    }

    private const val TAG = "GoogleTtsFallback"
}
