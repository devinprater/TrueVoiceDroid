package org.truevoicedroid.tts

/**
 * Translates the pitch and rate callers ask for into the numbers the
 * TruVoice engine takes.
 *
 * Ported from iTruVoice VoiceParameters, with the ceiling raised to the
 * extended range (TVTTS_RATE_MAX_EXT): the philosophy is the same —
 * neutral lands on the voice's own defaults, so an untouched voice sounds
 * untouched, and the full travel stays inside the engine's usable band.
 *
 * Android states rate and pitch as percentages where 100 is neutral
 * (SynthesisRequest.getSpeechRate/getPitch). The engine takes 46-400 wpm
 * (floors anything lower — load-bearing, because below 46 the rate table
 * index wraps and reads wildly) and absolute pitch 50-500, capped here at
 * 400, the furthest the inline escape can reach and still clearly speech.
 */
object VoiceParams {
    /** The slowest row of the engine's rate table. */
    const val SLOWEST_WPM = 46

    /** The ceiling with the OpenTV extended rate on. */
    const val FASTEST_WPM = 400

    /** The lowest pitch the engine holds. */
    const val LOWEST_PITCH = 50

    /** The highest pitch honored here. */
    const val HIGHEST_PITCH = 400

    /**
     * Words per minute for an Android rate percent (100 = neutral), given
     * the voice's default. Two straight segments meeting at neutral, so
     * 50, 100 and 200 land exactly on the slowest row, the voice's default
     * and the ceiling. Clamped to 46-400.
     */
    fun rateForAndroid(ratePercent: Int, defaultWpm: Int): Int {
        val factor = (ratePercent / 100.0).coerceIn(0.25, 4.0)
        val wpm = if (factor <= 1.0) {
            defaultWpm - (1.0 - factor) * 2.0 * (defaultWpm - SLOWEST_WPM)
        } else {
            defaultWpm + (factor - 1.0) * (FASTEST_WPM - defaultWpm).toDouble()
        }
        return wpm.roundToInt().coerceIn(SLOWEST_WPM, FASTEST_WPM)
    }

    /**
     * Absolute pitch for an Android pitch percent (100 = neutral), given
     * the voice's default. Two segments meeting at neutral for the same
     * reason: the usable range is not centred on any one default (Peter's
     * is 85, Sidney's 50, Alex's 203), so each half of the travel maps onto
     * default-to-floor and default-to-ceiling.
     */
    fun pitchForAndroid(pitchPercent: Int, defaultPitch: Int): Int {
        val factor = (pitchPercent / 100.0).coerceIn(0.25, 4.0)
        val value = if (factor <= 1.0) {
            defaultPitch - (1.0 - factor) * 2.0 * (defaultPitch - LOWEST_PITCH)
        } else {
            defaultPitch + (factor - 1.0) * (HIGHEST_PITCH - defaultPitch).toDouble()
        }
        return value.roundToInt().coerceIn(LOWEST_PITCH, HIGHEST_PITCH)
    }

    /**
     * Words per minute for a VoiceOver/SSML-scale rate 0-100 (50 neutral),
     * given the voice's default. Used for SSML prosody pieces.
     */
    fun rateForVoiceOver(rate: Int, defaultWpm: Int): Int {
        val fraction = (rate / 100.0).coerceIn(0.0, 1.0)
        val wpm = if (fraction <= 0.5) {
            defaultWpm - (0.5 - fraction) * 2.0 * (defaultWpm - SLOWEST_WPM)
        } else {
            defaultWpm + (fraction - 0.5) * 2.0 * (FASTEST_WPM - defaultWpm)
        }
        return wpm.roundToInt().coerceIn(SLOWEST_WPM, FASTEST_WPM)
    }

    /** Absolute pitch for a VoiceOver/SSML-scale pitch 0-100 (50 neutral). */
    fun pitchForVoiceOver(pitch: Int, defaultPitch: Int): Int {
        val fraction = (pitch / 100.0).coerceIn(0.0, 1.0)
        val value = if (fraction <= 0.5) {
            defaultPitch - (0.5 - fraction) * 2.0 * (defaultPitch - LOWEST_PITCH)
        } else {
            defaultPitch + (fraction - 0.5) * 2.0 * (HIGHEST_PITCH - defaultPitch)
        }
        return value.roundToInt().coerceIn(LOWEST_PITCH, HIGHEST_PITCH)
    }

    /** Linear gain for a 0-100 volume: 60 ("medium") is 0.6, 100 is full. */
    fun gainForVolume(volume: Int?): Float {
        if (volume == null) return 1.0f
        return (volume / 100.0f).coerceIn(0f, 1f)
    }

    /**
     * Base loudness lift applied on every path, under the caller's volume.
     * Measured on the built engine: peaks run well below full scale, so
     * doubling puts RMS in normal TTS territory. Volume 0 still silences.
     */
    const val BASE_BOOST = 2.0f

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
}
