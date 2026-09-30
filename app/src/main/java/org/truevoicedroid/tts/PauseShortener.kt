package org.truevoicedroid.tts

import kotlin.math.abs

/**
 * Caps runs of near-silence, Panthera-style ("fewest pauses"): the first
 * [maxPauseMs] of any silent run passes through untouched so word rhythm
 * survives, anything beyond it is dropped. Stateful within one utterance —
 * feed samples in order; [feed] returns the sample to emit or null to drop
 * it, so word-mark positions can be remapped while walking.
 */
class PauseShortener(
    rateHz: Int,
    maxPauseMs: Int = 200,
    private val threshold: Int = 150
) {
    private val maxSilent = (rateHz * maxPauseMs / 1000).coerceAtLeast(1)
    private var silentRun = 0

    /**
     * Copies up to [len] samples, shortening silences. Returns the number
     * of samples written to [out] (which must hold at least [len]).
     */
    fun process(input: ShortArray, len: Int, out: ShortArray): Int {
        var w = 0
        for (i in 0 until len) {
            val emit = feed(input[i])
            if (emit != null) out[w++] = emit
        }
        return w
    }

    /** Single-sample step: returns the sample to emit, or null to drop it. */
    fun feed(s: Short): Short? {
        if (abs(s.toInt()) < threshold) {
            silentRun++
            if (silentRun > maxSilent) return null
        } else {
            silentRun = 0
        }
        return s
    }
}
