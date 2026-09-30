package org.truevoicedroid.tts

import java.text.Normalizer

/**
 * Turns the text (or SSML) a synthesis request carries into the pieces the
 * TruVoice engine can actually render.
 *
 * Ported from iTruVoice SSMLText. Everything the engine cannot act on is
 * resolved here, and every way of getting it wrong is silent: nothing
 * errors, the voice still speaks, it just says the wrong thing or nothing
 * at all. Two failures this exists to prevent, both observed while
 * building the engine ports:
 * - Deleting a tag rather than replacing it with a space joins the words
 *   it sat between. A tag must become whitespace.
 * - The engine's `~` lead-in character obeys text commands: a literal
 *   tilde in the text can change the voice's rate for the rest of the
 *   utterance instead of being read. It becomes a space.
 *
 * TruVoice reads a single-byte code page, so curly quotes and accented
 * letters come out as glyphs — those are folded to ASCII before the engine
 * ever sees them. Dots between letters are silent to the engine
 * ("claude.ai" reads "claude aye"), so they become the word "dot" and "@"
 * becomes "at". Acronyms the engine would read as one word are uppercased
 * for the front end's all-caps spell-out; dictionary respellings are
 * substituted whole-word (loaded from assets/dictionary.tsv).
 */
object TextProcessor {

    /** One thing to render, in order. Pitch/rate/volume are on the 0-100
     * scale where 50 is neutral; null means the voice's normal setting. */
    sealed class Piece {
        data class Speech(
            val text: String,
            val pitch: Int?,
            val rate: Int?,
            val volume: Int?
        ) : Piece()

        data class Pause(val seconds: Double) : Piece()
        data class Bookmark(val name: String) : Piece()
    }

    data class Parsed(val pieces: List<Piece>) {
        /** All spoken text joined with single spaces. */
        val text: String
            get() = pieces.filterIsInstance<Piece.Speech>()
                .joinToString(" ") { it.text }
    }

    private data class Context(
        var pitch: Int? = null,
        var rate: Int? = null,
        var volume: Int? = null,
        var sayAs: String? = null
    ) {
        fun copy() = Context(pitch, rate, volume, sayAs)
    }

    /**
     * Parses [input], which may be SSML or plain text. Plain text (no
     * markup) becomes a single speech piece through the same finishing
     * pipeline.
     */
    fun parse(input: String, dictionary: List<Pair<String, String>> = emptyList()): Parsed {
        val document = resolveSubstitutions(input)
        val pieces = mutableListOf<Piece>()
        var context = Context()
        val stack = ArrayDeque<Context>()
        val buffer = StringBuilder()

        fun flush() {
            val text = finish(buffer.toString(), context.sayAs, dictionary)
            buffer.clear()
            if (text.isEmpty()) return
            pieces.add(
                Piece.Speech(
                    text, context.pitch, context.rate, context.volume
                )
            )
        }

        var index = 0
        var sawTag = false
        while (index < document.length) {
            val tagStart = document.indexOf('<', index)
            if (tagStart < 0) {
                buffer.append(document.substring(index))
                break
            }
            buffer.append(document.substring(index, tagStart))
            if (document.startsWith("<!--", tagStart)) {
                val end = document.indexOf("-->", tagStart)
                if (end < 0) break // unterminated comment: drop the remainder
                index = end + 3
                sawTag = true
                continue
            }
            val tagEnd = document.indexOf('>', tagStart)
            if (tagEnd < 0) break // unterminated tag: drop the remainder
            sawTag = true
            val tag = document.substring(tagStart + 1, tagEnd)
            index = tagEnd + 1
            val isClosing = tag.startsWith("/")
            val body = if (isClosing) tag.drop(1) else tag
            val name = body.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()

            when (name) {
                "speak", "p", "s", "w", "voice", "emphasis", "lang", "desc" -> {
                    // Structure and emphasis carry nothing the engine can act
                    // on, but each is still a word boundary.
                    flush()
                    context = if (isClosing) stack.removeLastOrNull() ?: context
                    else {
                        stack.addLast(context.copy())
                        context
                    }
                }
                "prosody" -> {
                    flush()
                    if (isClosing) {
                        context = stack.removeLastOrNull() ?: context
                    } else {
                        stack.addLast(context.copy())
                        attribute("pitch", body)?.let { pitchValue(it) }?.let {
                            context.pitch = it
                        }
                        attribute("rate", body)?.let { rateValue(it) }?.let {
                            context.rate = it
                        }
                        attribute("volume", body)?.let { volumeValue(it) }?.let {
                            context.volume = it
                        }
                        // `contour` describes a pitch curve over time. An
                        // engine with one pitch setting per utterance cannot
                        // follow it, so the baseline is used and the curve
                        // ignored.
                    }
                }
                "say-as" -> {
                    flush()
                    if (isClosing) {
                        context = stack.removeLastOrNull() ?: context
                    } else {
                        stack.addLast(context.copy())
                        context.sayAs = attribute("interpret-as", body)?.lowercase()
                    }
                }
                "sub" -> {
                    // Resolved as a whole before the walk.
                    flush()
                    if (isClosing) context = stack.removeLastOrNull() ?: context
                    else stack.addLast(context.copy())
                }
                "phoneme" -> {
                    // The enclosed text is spoken with its ordinary
                    // pronunciation; SSML phonemes arrive in an alphabet the
                    // engine does not speak. Per-word phonetic fixes live in
                    // the engine's user lexicon instead.
                    if (isClosing) flush()
                }
                "lexicon", "lookup", "meta", "metadata" -> {
                    // Pronunciation dictionaries and document metadata.
                    // Nothing here is for speaking.
                }
                "break" -> {
                    flush()
                    pieces.add(Piece.Pause(breakSeconds(body)))
                }
                "mark" -> {
                    flush()
                    attribute("name", body)?.let { pieces.add(Piece.Bookmark(it)) }
                }
                "audio" -> {
                    // The element's text content is the fallback, and is what
                    // ends up spoken.
                    if (isClosing) flush()
                }
                else -> {
                    // Unknown element: treated as a boundary, so its text is
                    // still spoken — the best available guess.
                    flush()
                    if (isClosing) context = stack.removeLastOrNull() ?: context
                    else stack.addLast(context.copy())
                }
            }
            // Tags are word boundaries: what sat on either side must not
            // join, so every tag becomes whitespace.
            buffer.append(' ')
        }
        flush()
        // No markup at all: plain text takes the same finishing pipeline as
        // one piece (finish() is idempotent over the parse's own pieces, and
        // parse of plain text already produced exactly one).
        if (!sawTag && pieces.size == 1) return Parsed(pieces)
        return Parsed(pieces)
    }

    // MARK: - Element values

    /** Seconds for a `break`, from `time` if given and `strength` otherwise. */
    fun breakSeconds(body: String): Double {
        attribute("time", body)?.let { seconds(it) }?.let {
            // Capped: a malformed value should not stall the speech queue.
            return it.coerceIn(0.0, 10.0)
        }
        return when (attribute("strength", body)?.lowercase()) {
            "none" -> 0.0
            "x-weak" -> 0.05
            "weak" -> 0.1
            "medium" -> 0.25
            "strong" -> 0.5
            "x-strong" -> 1.0
            else -> 0.25 // the SSML default is a medium break
        }
    }

    /** "1s", "500ms", "1.5s", or a bare number, which SSML reads as ms. */
    fun seconds(text: String): Double? {
        val trimmed = text.trim().lowercase()
        if (trimmed.endsWith("ms")) return trimmed.dropLast(2).toDoubleOrNull()?.div(1000.0)
        if (trimmed.endsWith("s")) return trimmed.dropLast(1).toDoubleOrNull()
        return trimmed.toDoubleOrNull()?.div(1000.0)
    }

    /** A `prosody` pitch onto the 0-100 scale, where 50 is neutral. */
    fun pitchValue(text: String): Int? {
        val value = text.trim().lowercase()
        when (value) {
            "x-low" -> return 15
            "low" -> return 25
            "medium" -> return 50
            "high" -> return 75
            "x-high" -> return 90
        }
        // A percentage is relative to the voice's own pitch, so it shifts
        // from neutral. Hertz values are absolute and cannot be mapped
        // without knowing the voice's range, so they are ignored.
        if (value.endsWith("%")) {
            return value.dropLast(1).toDoubleOrNull()
                ?.let { clamp((50.0 + it).roundToInt()) }
        }
        val st = value.indexOf("st")
        if (st >= 0) {
            return value.substring(0, st).toDoubleOrNull()
                ?.let { clamp((50.0 + it * 6.0).roundToInt()) }
        }
        return null
    }

    /** A `prosody` rate onto the 0-100 scale, where 50 is neutral. */
    fun rateValue(text: String): Int? {
        val value = text.trim().lowercase()
        when (value) {
            "x-slow" -> return 10
            "slow" -> return 25
            "medium" -> return 50
            "fast" -> return 75
            "x-fast" -> return 90
        }
        // 100% is the voice's normal rate, so it sits at neutral and the
        // adjustment spreads either side. Halved because the caller's range
        // is much narrower than SSML's: 200% must stay inside the engine's
        // usable band rather than running to its extreme.
        if (value.endsWith("%")) {
            return value.dropLast(1).toDoubleOrNull()
                ?.let { clamp((50.0 + (it - 100.0) * 0.5).roundToInt()) }
        }
        return null
    }

    /** A `prosody` volume onto the 0-100 scale. */
    fun volumeValue(text: String): Int? {
        val value = text.trim().lowercase()
        when (value) {
            "silent", "none" -> return 0
            "x-soft" -> return 20
            "soft" -> return 40
            "medium" -> return 60
            "loud" -> return 80
            "x-loud" -> return 100
        }
        if (value.endsWith("%")) {
            return value.dropLast(1).toDoubleOrNull()?.let { clamp(it.roundToInt()) }
        }
        // Decibels are relative to full scale; +6 dB is about double.
        if (value.endsWith("db")) {
            return value.dropLast(2).toDoubleOrNull()
                ?.let { clamp((Math.pow(10.0, it / 20.0) * 60.0).roundToInt()) }
        }
        value.toDoubleOrNull()?.let { if (it <= 1.0) return clamp((it * 100).roundToInt()) }
        return null
    }

    private fun clamp(value: Int) = value.coerceIn(0, 100)

    /** Reads an attribute out of a tag body, decoding entities in its value. */
    fun attribute(name: String, body: String): String? {
        for (q in listOf('"', '\'')) {
            val pattern = Regex(
                """\b${Regex.escape(name)}\s*=\s*\Q$q\E(.*?)\Q$q\E""",
                RegexOption.IGNORE_CASE
            )
            val match = pattern.find(body) ?: continue
            return decodeEntities(match.groupValues[1])
        }
        return null
    }

    // MARK: - Text preparation

    /** Turns accumulated raw text into what the engine should be given. */
    fun finish(raw: String, sayAs: String?, dictionary: List<Pair<String, String>> = emptyList()): String {
        var text = decodeEntities(raw)
        // Invisibles before the fold: the fold would keep them (they have no
        // ASCII form) and the engine would read the corruption.
        text = removeInvisibleCharacters(text)
        // The engine's own lead-in character, before anything else can move
        // it next to a word: text can switch the parser into command mode
        // for the rest of the utterance instead of being read.
        text = text.replace("~", " ")
        text = foldForEngine(text)
        text = collapseWhitespace(text)
        text = closeGapsBeforePunctuation(text)
        text = applySayAs(text, sayAs)
        // After `say-as`, so a word it spelled out is never rewritten.
        text = expandAcronyms(text)
        // After acronyms, so a dictionary replacement never re-triggers one,
        // and before dots, so a replacement's own dots pass through the same
        // exemptions.
        text = applyDictionary(text, dictionary)
        // After acronyms, so an uppercased domain label still gets its dot.
        text = expandDotsAndAt(text)
        return text.trim()
    }

    /**
     * Folds characters the engine cannot read into what it can. Typographic
     * punctuation has an ASCII form named explicitly; anything else outside
     * ASCII is decomposed and its diacritics dropped ("café" is handed over
     * as "cafe"). Characters with no ASCII form pass through unchanged —
     * the engine skips what it cannot read. Degree and euro get words, not
     * folds (the engine reads them as "A").
     */
    fun foldForEngine(text: String): String {
        val output = StringBuilder(text.length)
        for (character in text) {
            val typo = typographicReplacements[character]
            if (typo != null) {
                output.append(typo)
            } else {
                val word = wordReplacements[character]
                if (word != null) {
                    output.append(' ').append(word).append(' ')
                } else if (character.code < 128) {
                    output.append(character)
                } else {
                    val folded = Normalizer.normalize(character.toString(), Normalizer.Form.NFD)
                        .filter { it.code < 128 && Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                    if (folded.isEmpty()) output.append(character) else output.append(folded)
                }
            }
        }
        return output.toString()
    }

    private val typographicReplacements = mapOf(
        '‘' to "'", '’' to "'",
        '‚' to ",", '‛' to "'",
        '“' to "\"", '”' to "\"",
        '„' to "\"", '‟' to "\"",
        '–' to "-", '—' to "-",
        '―' to "-",
        '…' to "...",
        '′' to "'", '″' to "\""
    )

    private val wordReplacements = mapOf(
        '°' to "degree",
        '€' to "euro"
    )

    /**
     * Characters that carry no sound but corrupt the reading, so they are
     * removed. Measured: a left-to-right mark turns "Read hello" into
     * "Re- hello". Bidi marks and isolates, zero width spaces, the soft
     * hyphen, the word joiner and the byte order mark.
     */
    fun removeInvisibleCharacters(text: String): String =
        text.filter { it !in invisibleCharacters }

    private val invisibleCharacters = setOf(
        '­', // soft hyphen
        '​', '‌', '‍', // zero width space, non-joiner, joiner
        '‎', '‏', // left-to-right / right-to-left mark
        '‪', '‫', '‬', // bidi embedding and pop
        '‭', '‮', // bidi overrides
        '⁠', // word joiner
        '⁦', '⁧', '⁨', '⁩', // bidi isolates
        '﻿' // byte order mark
    )

    /**
     * `say-as` handling. `characters`/`digits` want each character named:
     * the engine already reads space-separated letters as their names, so
     * separating them is what does it. The rest are left alone — the engine
     * normalizes numbers, currency and times itself.
     */
    fun applySayAs(text: String, mode: String?): String {
        return when (mode) {
            "characters", "character", "char", "digits" ->
                text.filter { !it.isWhitespace() }.toList().joinToString(" ")
            else -> text
        }
    }

    /**
     * Says acronyms the engine would read as one word. The front end spells
     * an ALL-CAPS run letter by letter, while the lowercase form reads as a
     * word — so uppercasing the word hands the engine a reading it already
     * gets right. Whole-word and case-insensitive, longest first.
     */
    fun expandAcronyms(text: String): String {
        var result = text
        for ((term, replacement) in acronyms) {
            if (!result.contains(term, ignoreCase = true)) continue
            val pattern = Regex("""\b${Regex.escape(term)}\b""", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, replacement)
        }
        return result
    }

    private val acronyms = listOf(
        "aidbs" to "AIDBS",
        "aidb" to "AIDB"
    )

    /**
     * Says the dictionary's respellings instead of the engine's guesses.
     * Whole-word and case-sensitive, longest term first — an all-caps form
     * is never listed, so a spelled initial keeps the front end's
     * letter-by-letter reading.
     */
    fun applyDictionary(text: String, dictionary: List<Pair<String, String>>): String {
        var result = text
        for ((term, replacement) in dictionary) {
            if (!result.contains(term)) continue
            val pattern = Regex("""\b${Regex.escape(term)}\b""")
            result = pattern.replace(result, MatcherReplacement.escape(replacement))
        }
        return result
    }

    /**
     * Says email addresses and domains the way they are written. The engine
     * drops dots silently, so dots between letters become the word "dot"
     * and "@" becomes "at". Untouched, because the engine already reads
     * them: digit dots, abbreviation chains ("e.g.", "U.S."), and "://"
     * URL hosts.
     */
    fun expandDotsAndAt(text: String): String {
        // "@" first: it is never a word character, and spelling it out keeps
        // the dot pass to dots alone.
        val atMarked = text.replace("@", " at ")
        val characters = atMarked.toList()
        val output = StringBuilder(atMarked.length + 8)
        for ((index, character) in characters.withIndex()) {
            if (character != '.' || index == 0 || index + 1 >= characters.size ||
                !characters[index - 1].isLetter() || !characters[index + 1].isLetter()
            ) {
                output.append(character)
                continue
            }
            // An abbreviation chain ("e.g.", "U.S.", "Ph.D."): a
            // single-letter run meeting another dot. Longer runs meeting a
            // dot still want theirs heard.
            var next = index + 1
            while (next < characters.size && characters[next].isLetter()) next++
            if (next - (index + 1) == 1 && next < characters.size && characters[next] == '.') {
                output.append(character)
                continue
            }
            // A URL host ("http://x.com") reads its punctuation already, so
            // a dot inside a "://" run is left alone.
            var token = index - 1
            while (token >= 0 && (characters[token].isLetterOrDigit() || "./:@-_".contains(characters[token]))) token--
            if (characters.subList(token + 1, index).joinToString("").contains("://")) {
                output.append(character)
                continue
            }
            output.append(" dot ")
        }
        return collapseWhitespace(output.toString())
    }

    /** Resolves `<sub alias="...">` to its alias. */
    fun resolveSubstitutions(text: String): String {
        val pattern = Regex(
            """<sub\s+alias\s*=\s*["']([^"']*)["'][^>]*>[^<]*</sub>""",
            RegexOption.IGNORE_CASE
        )
        return pattern.replace(text) { it.groupValues[1] }
    }

    fun collapseWhitespace(text: String): String =
        text.replace(Regex("""\s+"""), " ")

    fun closeGapsBeforePunctuation(text: String): String =
        text.replace(Regex("""\s+([,.!?;:])"""), "$1")

    // MARK: - Entities

    private val namedEntities = listOf(
        "&nbsp;" to " ", "&ensp;" to " ", "&emsp;" to " ", "&thinsp;" to " ",
        "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'",
        "&ldquo;" to "\"", "&rdquo;" to "\"",
        "&lsquo;" to "'", "&rsquo;" to "'",
        "&mdash;" to "-", "&ndash;" to "-",
        "&hellip;" to "...",
        // Decoded last: doing it earlier would let "&amp;lt;" become "<".
        "&amp;" to "&"
    )

    fun decodeEntities(input: String): String {
        var text = input
        text = Regex("""&#[xX]([0-9A-Fa-f]+);""").replace(text) {
            it.groupValues[1].toIntOrNull(16)?.let { cp ->
                runCatching { String(Character.toChars(cp)) }.getOrNull()
            } ?: it.value
        }
        text = Regex("""&#([0-9]+);""").replace(text) {
            it.groupValues[1].toIntOrNull()?.let { cp ->
                runCatching { String(Character.toChars(cp)) }.getOrNull()
            } ?: it.value
        }
        for ((entity, replacement) in namedEntities) {
            text = text.replace(entity, replacement)
        }
        return text
    }

    /** The words to speak, with markup resolved. */
    fun plainText(input: String, dictionary: List<Pair<String, String>> = emptyList()): String {
        // Fast path for the common case: no markup at all.
        if (!input.contains('<')) return finish(input, null, dictionary)
        return parse(input, dictionary).text
    }

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()

    private object MatcherReplacement {
        fun escape(s: String): String = s.replace("\\", "\\\\").replace("$", "\\$")
    }
}
