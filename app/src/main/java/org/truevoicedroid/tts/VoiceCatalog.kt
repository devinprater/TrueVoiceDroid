package org.truevoicedroid.tts

/**
 * The voices the service offers: the engine's twenty, ten per language.
 *
 * Ported from iTruVoice VoiceCatalog/TruVoice: the names come from the
 * engine data in registration order (voice 0 is Peter, voice 10 is
 * Pedro). Indices stay plain integers 0-19. Index picks both the
 * parameter row and the engine — 0-9 run English, 10-19 Spanish.
 */
object VoiceCatalog {
    const val SAMPLE_RATE_HZ = 16000

    data class VoiceInfo(val index: Int, val name: String, val language: String)

    private val names = listOf(
        "Peter", "Sidney", "Eager Eddie", "Deep Douglas", "Biff",
        "Grandpa Amos", "Melvin", "Alex", "Wanda", "Julia",
        "Pedro", "Jorge", "Ricardo", "Paco", "Luis",
        "Ezequiel", "Rogelio", "Carlos", "Josefa", "Isabel"
    )

    val all: List<VoiceInfo> = names.mapIndexed { i, name ->
        VoiceInfo(i, name, if (i < 10) "en-US" else "es-ES")
    }

    const val DEFAULT_VOICE = 0 // Peter, the voice the tables were written for

    fun languageFor(voice: Int): String = if (voice in 0..9) "en" else "es"

    fun infoFor(voice: Int): VoiceInfo? = all.firstOrNull { it.index == voice }

    /** Android TTS voice name: stable, unique, readable. */
    fun androidNameFor(voice: Int): String = "TruVoice ${names.getOrElse(voice) { voice }}"

    fun indexFromAndroidName(name: String?): Int? {
        if (name == null) return null
        return all.firstOrNull { androidNameFor(it.index) == name }?.index
    }

    fun sampleText(voice: Int): String =
        if (voice in 0..9) "Hello world. This is TruVoice speaking."
        else "Hola mundo. Esta es la voz TruVoice."
}
