package org.truevoicedroid.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The TTS service's handle on the engine worker process.
 *
 * Same public surface the service used to get from [TruVoiceEngine], so the
 * service keeps its voice, text and streaming logic and only the engine
 * moves out of process.
 */
class TruVoiceWorkerClient(private val appContext: Context) {

    companion object {
        private const val TAG = "TruVoiceWorkers"
        private const val BIND_TIMEOUT_S = 20L
        private const val DIE_TIMEOUT_S = 5L
        private const val PULL_BYTES = 64 * 1024
        private const val PULL_WAIT_MS = 200
        private const val SAY_WAIT_MS = 400
    }

    private class Connection(val context: Context) : ServiceConnection {
        private val gate = Any()
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var api: ITruVoiceWorker? = null
        @Volatile var retired = false
        var bound = false

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(gate) {
                if (retired) return
                api = ITruVoiceWorker.Stub.asInterface(binder)
                try {
                    binder.linkToDeath({ disconnected() }, 0)
                } catch (e: DeadObjectException) {
                    disconnected()
                }
                ready.countDown()
            }
        }

        private fun unbind() {
            if (bound) {
                try {
                    context.unbindService(this)
                } catch (t: Throwable) {
                    Log.w(TAG, "unbind failed", t)
                }
                bound = false
            }
        }

        private fun disconnected() {
            synchronized(gate) {
                retired = true
                unbind()
                ready.countDown()
                closed.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = disconnected()
        override fun onNullBinding(name: ComponentName?) = disconnected()

        override fun onBindingDied(name: ComponentName?) {
            synchronized(gate) {
                api = null
                retire()
            }
        }

        fun retire() {
            if (retired) return
            retired = true
            unbind()
            ready.countDown()
            val target = api
            api = null
            if (target == null || !target.asBinder().isBinderAlive) {
                closed.countDown()
                return
            }
            try {
                target.shutdown()
            } catch (e: DeadObjectException) {
                closed.countDown()
            } catch (t: Throwable) {
                Log.w(TAG, "shutdown failed", t)
                closed.countDown()
            }
        }
    }

    private val lock = Any()
    private var connection: Connection? = null

    @Volatile private var activeRequest: Int = -1
    @Volatile private var cancelled = false
    @Volatile private var sampleRateCache: Int = 0
    private var dictionaryCache: List<Pair<String, String>>? = null
    private var markCache: Array<String>? = null

    // ------------------------------------------------------------------
    // Worker lifecycle
    // ------------------------------------------------------------------

    private fun worker(): ITruVoiceWorker {
        check(Looper.myLooper() != Looper.getMainLooper()) { "worker binding on main thread" }
        val conn: Connection
        synchronized(lock) {
            val existing = connection
            if (existing != null && !existing.retired) {
                existing.api?.let { if (it.asBinder().isBinderAlive) return it }
                existing.retire()
            }
            conn = Connection(appContext).also { c ->
                c.bound = appContext.bindService(
                    Intent(appContext, TruVoiceWorkerService::class.java),
                    c,
                    Context.BIND_AUTO_CREATE
                )
                if (!c.bound) {
                    c.ready.countDown()
                    c.closed.countDown()
                }
                connection = c
            }
        }
        if (!conn.ready.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) {
            retireConnection(conn)
            throw IllegalStateException("engine worker bind timed out")
        }
        val api = conn.api
        if (conn.retired || api == null || !api.asBinder().isBinderAlive) {
            retireConnection(conn)
            throw IllegalStateException("engine worker unavailable")
        }
        return api
    }

    /** Kills and drops the current worker; the next request binds a fresh one. */
    fun retireWorker() {
        val conn = synchronized(lock) { connection }
        if (conn != null) retireConnection(conn)
    }

    private fun retireConnection(conn: Connection) {
        synchronized(lock) { conn.retire() }
        if (!conn.closed.await(DIE_TIMEOUT_S, TimeUnit.SECONDS)) {
            Log.w(TAG, "worker did not report death")
        }
        synchronized(lock) { if (connection === conn) connection = null }
    }

    private fun retireIfCurrent(api: ITruVoiceWorker) {
        val conn = synchronized(lock) {
            connection?.takeIf { it.api?.asBinder() === api.asBinder() }
        } ?: return
        retireConnection(conn)
    }

    fun shutdown() = retireWorker()

    // ------------------------------------------------------------------
    // Engine state, forwarded
    // ------------------------------------------------------------------

    fun sampleRateHz(): Int = sampleRateCache.takeIf { it > 0 } ?: try {
        worker().sampleRate().also { if (it > 0) sampleRateCache = it }
    } catch (t: Throwable) {
        Log.w(TAG, "sampleRate failed", t)
        VoiceCatalog.SAMPLE_RATE_HZ
    }

    fun setSampleRateHz(hz: Int): Int = try {
        worker().setSampleRate(hz).also { if (it > 0) sampleRateCache = it }
    } catch (t: Throwable) {
        Log.w(TAG, "setSampleRate failed", t)
        VoiceCatalog.SAMPLE_RATE_HZ
    }

    fun defaultRateWpm(voice: Int): Int = try {
        worker().defaultRate(voice).takeIf { it > 0 } ?: 150
    } catch (t: Throwable) {
        150
    }

    fun defaultPitch(voice: Int): Int = try {
        worker().defaultPitch(voice).takeIf { it > 0 } ?: 85
    } catch (t: Throwable) {
        85
    }

    fun dictionary(): List<Pair<String, String>> {
        dictionaryCache?.let { return it }
        val flat = try {
            worker().dictionary()
        } catch (t: Throwable) {
            Log.w(TAG, "dictionary failed", t)
            return emptyList()
        }
        val pairs = ArrayList<Pair<String, String>>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            pairs.add(flat[i] to flat[i + 1])
            i += 2
        }
        dictionaryCache = pairs
        return pairs
    }

    private fun markSequences(): Array<String> {
        markCache?.let { return it }
        val marks = try {
            worker().markSequences()
        } catch (t: Throwable) {
            Log.w(TAG, "markSequences failed", t)
            return emptyArray()
        }
        markCache = marks
        return marks
    }

    /** [plain] split into engine texts of at most [maxWords] words each. */
    fun buildEngineTexts(plain: String, maxWords: Int = 200): List<TruVoiceEngine.EngineText> {
        val words = plain.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(TruVoiceEngine.EngineText("", "", emptyList()))
        val marks = markSequences()
        return words.chunked(maxWords.coerceAtLeast(1)).map { chunk ->
            val engine = StringBuilder(chunk.sumOf { it.length } + chunk.size * 8)
            val plainBuilder = StringBuilder(engine.capacity())
            val spans = mutableListOf<TruVoiceEngine.WordSpan>()
            chunk.forEachIndexed { i, word ->
                val id = i + 1
                val escape = marks.getOrNull(id - 1)?.takeIf { it.isNotEmpty() }
                if (plainBuilder.isNotEmpty()) {
                    engine.append(' ')
                    plainBuilder.append(' ')
                }
                if (escape != null) engine.append(escape)
                val start = plainBuilder.length
                engine.append(word)
                plainBuilder.append(word)
                spans.add(TruVoiceEngine.WordSpan(id, start, start + word.length))
            }
            TruVoiceEngine.EngineText(engine.toString(), plainBuilder.toString(), spans)
        }
    }

    // ------------------------------------------------------------------
    // Synthesis
    // ------------------------------------------------------------------

    fun producesSpeech(utterance: TruVoiceEngine.Utterance?): Boolean {
        if (utterance == null || utterance.samples.isEmpty()) return false
        return utterance.samples.any { it != 0.toShort() }
    }

    /** Asks the engine to stop after the current step. Never interrupts it. */
    fun cancelActive() {
        cancelled = true
        val id = activeRequest
        if (id <= 0) return
        val api = synchronized(lock) { connection?.api } ?: return
        try {
            api.cancel(id)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel failed", t)
        }
    }

    fun synthesize(voice: Int, wpm: Int, pitch: Int, engineText: String): TruVoiceEngine.Utterance? {
        val api = try {
            worker()
        } catch (t: Throwable) {
            Log.w(TAG, "no engine worker", t)
            return null
        }
        cancelled = false
        val id = try {
            api.say(voice, wpm, pitch, engineText, SAY_WAIT_MS)
        } catch (t: Throwable) {
            retireIfCurrent(api)
            return null
        }
        if (id == TruVoiceWorkerService.BUSY) {
            Log.w(TAG, "engine stayed busy: retiring worker")
            retireIfCurrent(api)
            return null
        }
        if (id < 0) return null
        activeRequest = id
        val deadline = System.currentTimeMillis() +
            GoogleTtsFallback.nativeTimeoutMs(engineText.length)
        val buffer = ByteArrayOutputStream(engineText.length * 16)
        try {
            while (true) {
                if (cancelled) {
                    try {
                        api.cancel(id)
                    } catch (t: Throwable) {
                        Log.w(TAG, "cancel failed", t)
                    }
                    return null
                }
                val block = api.pull(id, PULL_BYTES, PULL_WAIT_MS)
                if (block != null && block.isNotEmpty()) {
                    buffer.write(block)
                    continue
                }
                if (api.finished(id)) break
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "synthesis deadline passed: retiring worker")
                    retireIfCurrent(api)
                    return null
                }
            }
            val marks = api.marks(id)
            val list = ArrayList<TruVoiceEngine.Mark>(marks.size / 2)
            var i = 0
            while (i + 1 < marks.size) {
                list.add(TruVoiceEngine.Mark(marks[i], marks[i + 1]))
                i += 2
            }
            val samples = littleEndianToShorts(buffer.toByteArray())
            if (samples.isEmpty()) return null
            return TruVoiceEngine.Utterance(samples, list)
        } catch (t: Throwable) {
            Log.w(TAG, "synthesis failed", t)
            retireIfCurrent(api)
            return null
        } finally {
            activeRequest = -1
        }
    }

    private fun littleEndianToShorts(bytes: ByteArray): ShortArray {
        val count = bytes.size / 2
        if (count == 0) return ShortArray(0)
        val out = ShortArray(count)
        var j = 0
        for (i in 0 until count) {
            out[i] = ((bytes[j].toInt() and 0xFF) or (bytes[j + 1].toInt() shl 8)).toShort()
            j += 2
        }
        return out
    }
}
