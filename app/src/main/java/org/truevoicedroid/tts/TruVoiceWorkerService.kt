package org.truevoicedroid.tts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors

/**
 * Holds the TruVoice engine and everything it remembers between requests:
 * one synth handle per voice, the lexicon, and the output sample rate.
 *
 * One request at a time on one thread.  Cancellation only flips a flag; a
 * native call that will not return is cleared by the owner killing this
 * process, never by interrupting the thread that is inside it.
 */
class TruVoiceWorkerService : Service() {

    companion object {
        private const val TAG = "TruVoiceWorker"
        /** say() answers this when the previous request is still running. */
        const val BUSY = -2
        /** Debug-only text prefix that makes the engine hang, to prove recovery. */
        const val HANG_PREFIX = "\$HANG"
    }

    private lateinit var engine: TruVoiceEngine

    private val synthesis = Executors.newSingleThreadExecutor { r ->
        Thread(r, "truvoice-engine").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    private val state = Object()
    private var currentId = 0
    private var busy = false
    private var finished = true
    private var cancelled = false
    private var pending: ByteArray? = null
    private var pendingMarks: IntArray = IntArray(0)

    @Volatile private var markEscapes: Array<String> = emptyArray()
    @Volatile private var dictionaryPairs: Array<String> = emptyArray()

    private val binder = object : ITruVoiceWorker.Stub() {

        override fun say(voice: Int, wpm: Int, pitch: Int, text: String, waitMs: Int): Int {
            val id: Int
            synchronized(state) {
                val deadline = System.currentTimeMillis() + waitMs.coerceIn(0, 5_000)
                while (busy) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) {
                        Log.w(TAG, "say refused: request $currentId still running")
                        return BUSY
                    }
                    try {
                        (state as java.lang.Object).wait(left)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return BUSY
                    }
                }
                currentId += 1
                id = currentId
                busy = true
                finished = false
                cancelled = false
                pending = null
                pendingMarks = IntArray(0)
            }
            synthesis.submit { runRequest(id, voice, wpm, pitch, text) }
            return id
        }

        override fun pull(requestId: Int, maxBytes: Int, waitMs: Int): ByteArray? {
            val limit = maxBytes.coerceIn(1, 256 * 1024)
            val deadline = System.currentTimeMillis() + waitMs.coerceIn(0, 5_000)
            synchronized(state) {
                while (true) {
                    if (requestId != currentId) return null
                    if (cancelled) return null
                    val buf = pending
                    if (buf != null && buf.isNotEmpty()) {
                        val n = minOf(limit, buf.size)
                        val out = buf.copyOfRange(0, n)
                        pending = if (n == buf.size) null else buf.copyOfRange(n, buf.size)
                        return out
                    }
                    if (finished) return null
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return null
                    try {
                        (state as java.lang.Object).wait(left)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }

        override fun marks(requestId: Int): IntArray = synchronized(state) {
            if (requestId == currentId) pendingMarks else IntArray(0)
        }

        override fun finished(requestId: Int): Boolean = synchronized(state) {
            requestId == currentId && finished
        }

        override fun cancel(requestId: Int) {
            synchronized(state) {
                if (requestId == currentId) {
                    cancelled = true
                    pending = null
                    (state as java.lang.Object).notifyAll()
                }
            }
        }

        override fun shutdown() {
            Log.i(TAG, "retiring worker process")
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        override fun setSampleRate(hz: Int): Int = engine.setSampleRateHz(hz)

        override fun sampleRate(): Int = engine.sampleRateHz()

        override fun defaultRate(voice: Int): Int = engine.defaultRateWpm(voice)

        override fun defaultPitch(voice: Int): Int = engine.defaultPitch(voice)

        override fun dictionary(): Array<String> = dictionaryPairs

        override fun markSequences(): Array<String> = markEscapes
    }

    override fun onCreate() {
        super.onCreate()
        try {
            engine = TruVoiceEngine(applicationContext)
            val flat = ArrayList<String>()
            for (pair in engine.dictionary()) {
                flat.add(pair.first)
                flat.add(pair.second)
            }
            dictionaryPairs = flat.toTypedArray()
            // Mark ids are one byte, so 1..255 covers every chunk the service builds.
            markEscapes = Array(255) { i ->
                try {
                    TruVoiceNative.nativeMarkSequence(i + 1) ?: ""
                } catch (t: Throwable) {
                    Log.e(TAG, "markSequence failed for ${i + 1}", t)
                    ""
                }
            }
            // Warm the first voice's handle so the first utterance is not
            // paying for the engine's own start-up.
            engine.defaultRateWpm(0)
        } catch (t: Throwable) {
            Log.e(TAG, "engine did not load", t)
        }
    }

    private fun runRequest(id: Int, voice: Int, wpm: Int, pitch: Int, text: String) {
        val utterance = try {
            if (BuildConfig.DEBUG && text.contains(HANG_PREFIX)) {
                Log.w(TAG, "debug hang for request $id")
                Thread.sleep(120_000)
                null
            } else {
                engine.synthesize(voice, wpm, pitch, text)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize failed", t)
            null
        }
        synchronized(state) {
            if (id == currentId && !cancelled) {
                pending = utterance?.let { toBytes(it.samples) }
                pendingMarks = utterance?.let { u ->
                    val flat = IntArray(u.marks.size * 2)
                    u.marks.forEachIndexed { i, m ->
                        flat[i * 2] = m.id
                        flat[i * 2 + 1] = m.samplePos
                    }
                    flat
                } ?: IntArray(0)
            }
            busy = false
            finished = true
            (state as java.lang.Object).notifyAll()
        }
    }

    private fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (s in samples) {
            val v = s.toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        synthesis.shutdownNow()
        super.onDestroy()
    }
}
