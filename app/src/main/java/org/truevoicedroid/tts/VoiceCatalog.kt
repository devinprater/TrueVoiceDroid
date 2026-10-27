package org.truevoicedroid.tts

/**
 * The voices the service offers: the engine's thirty-three -- eleven American
 * English, eleven Spanish, eleven British English.
 *
 * The numbers are the engine's own and run across every language, so a number
 * is not a position here: English 0 through 10, Spanish 11 through 21,
 * Japanese 22 through 31 (compiled but not offered -- its front end needs the
 * 27 MB dictionary upstream loads from disk), British English 32 through 42.
 * Each voice carries the tag the system is told; the engine row it speaks is
 * derived from that tag.
 *
 * British English is the same engine with stage 3's vowel targets re-aimed at
 * General British, not a second set of recordings, so each British voice is
 * the American speaker of the same number minus 32 under a British name:
 * Peter is Nigel, Deep Douglas is Deep Duncan, Julia is Imogen.
 */
object VoiceCatalog {
    const val SAMPLE_RATE_HZ = 16000

    data class VoiceInfo(val index: Int, val name: String, val language: String)

    private val entries = listOf(
        VoiceInfo(0, "Peter", "en-US"),
        VoiceInfo(1, "Sidney", "en-US"),
        VoiceInfo(2, "Eager Eddie", "en-US"),
        VoiceInfo(3, "Deep Douglas", "en-US"),
        VoiceInfo(4, "Biff", "en-US"),
        VoiceInfo(5, "Grandpa Amos", "en-US"),
        VoiceInfo(6, "Melvin", "en-US"),
        VoiceInfo(7, "Alex", "en-US"),
        VoiceInfo(8, "Wanda", "en-US"),
        VoiceInfo(9, "Julia", "en-US"),
        VoiceInfo(10, "Frank", "en-US"),
        VoiceInfo(11, "Pedro", "es-ES"),
        VoiceInfo(12, "Jorge", "es-ES"),
        VoiceInfo(13, "Ricardo", "es-ES"),
        VoiceInfo(14, "Paco", "es-ES"),
        VoiceInfo(15, "Luis", "es-ES"),
        VoiceInfo(16, "Ezequiel", "es-ES"),
        VoiceInfo(17, "Rogelio", "es-ES"),
        VoiceInfo(18, "Carlos", "es-ES"),
        VoiceInfo(19, "Josefa", "es-ES"),
        VoiceInfo(20, "Isabel", "es-ES"),
        VoiceInfo(21, "Francisco", "es-ES"),
        VoiceInfo(32, "Nigel", "en-GB"),
        VoiceInfo(33, "Clive", "en-GB"),
        VoiceInfo(34, "Eager Edwin", "en-GB"),
        VoiceInfo(35, "Deep Duncan", "en-GB"),
        VoiceInfo(36, "Rupert", "en-GB"),
        VoiceInfo(37, "Gramps Amos", "en-GB"),
        VoiceInfo(38, "Malcolm", "en-GB"),
        VoiceInfo(39, "Alistair", "en-GB"),
        VoiceInfo(40, "Fiona", "en-GB"),
        VoiceInfo(41, "Imogen", "en-GB"),
        VoiceInfo(42, "Bernard", "en-GB"),
    )

    val all: List<VoiceInfo> = entries

    const val DEFAULT_VOICE = 0 // Peter, the voice the tables were written for

    fun infoFor(voice: Int): VoiceInfo? = entries.firstOrNull { it.index == voice }

    /** The engine language row a voice opens: "en", "es" or "en-GB". */
    fun languageFor(voice: Int): String = when {
        infoFor(voice)?.language == "en-GB" -> "en-GB"
        infoFor(voice)?.language?.startsWith("es") == true -> "es"
        else -> "en"
    }

    /**
     * The first voice for a BCP-47 tag ("en-GB", "es-ES"), falling back to the
     * bare language ("en") and then to Peter. TalkBack asks by language, so
     * this is what decides that an English (United Kingdom) request gets
     * Nigel rather than Peter.
     */
    fun defaultVoiceFor(tag: String?): Int {
        val want = tag?.lowercase()?.takeIf { it.isNotBlank() } ?: return DEFAULT_VOICE
        entries.firstOrNull { it.language.lowercase() == want }?.let { return it.index }
        val lang = want.substringBefore('-')
        entries.firstOrNull {
            it.language.lowercase().substringBefore('-') == lang
        }?.let { return it.index }
        return DEFAULT_VOICE
    }

    /** Whether [voice] speaks the BCP-47 [tag], at country or language level. */
    fun speaksTag(voice: Int, tag: String?): Boolean {
        val want = tag?.lowercase()?.takeIf { it.isNotBlank() } ?: return true
        val have = infoFor(voice)?.language?.lowercase() ?: return false
        return have == want || have.substringBefore('-') == want.substringBefore('-')
    }

    /** Android TTS voice name: stable, unique, readable. */
    fun androidNameFor(voice: Int): String = "TruVoice ${infoFor(voice)?.name ?: voice}"

    fun indexFromAndroidName(name: String?): Int? {
        if (name == null) return null
        return entries.firstOrNull { androidNameFor(it.index) == name }?.index
    }

    fun sampleText(voice: Int): String =
        if (languageFor(voice) == "es") "Hola mundo. Esta es la voz TruVoice."
        else "Hello world. This is TruVoice speaking."
}
