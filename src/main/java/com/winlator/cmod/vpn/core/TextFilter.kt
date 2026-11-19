package com.winlator.cmod.vpn.core

import java.text.Normalizer

// Decides whether text typed by a player (nickname, room name, game) may be used. Names also
// arrive from other players, so the same check runs on everything received.
//
// The lists are a baseline, not a complete one: extend them as needed. Entries are plain
// lowercase letters without accents (an "n with tilde" is folded to "ny", so write "conyo").
object TextFilter {
    // Whole words only, so ordinary words that merely contain them stay usable ("class", "cono").
    // An optional plural ("s" / "es") is accepted after each.
    private const val WORDS = """
        ass arse boner clit cock cum cunt kunt dick douche fag hentai horny kike milf nigga penis
        porn porno prick pussy rape rapist retard slut spic tits titties twat vagina wank wanker
        anal anus chink coon gook tranny wetback beaner paki
        puta puto putita putito zorra culo pene teta pija polla pollas verga vergon follar
        conyo punyeta hdp ctm ptm huevon boludo pelotudo marica mamon mamada pajero sudaca negrata
        pinche
        nazi heil reich fuhrer dachau
    """

    // Matched anywhere in the text once spaces and symbols are removed, which catches
    // "f u c k" and "f.u.c.k". Only for terms that appear inside no ordinary word.
    private const val SUBSTRINGS = """
        fuck shit bitch bastard nigger faggot whore asshole dumbass dickhead blowjob handjob
        cumshot dildo jizz orgasm bukkake rimjob bollocks
        mierda pendejo cabron hijueputa hijoputa hijodeputa chingad chingue culero maricon
        malparid gilipoll pelotud cojudo mamaguevo mamahuevo chupapoll conchetumare
        neonazi hitler heilhitler siegheil swastika gestapo meinkampf zyklon holohoax auschwitz
        thirdreich whitepower whitepride kkk
    """

    // Matched as written, before any folding.
    private val SYMBOLS = listOf(
        "\u5350", "\u534D", // swastikas
        "\u0FD5", "\u0FD6", "\u0FD7", "\u0FD8", // right and left facing svasti signs
        "\u16CB\u16CB", // two sig runes
        "\u03DF\u03DF", // two lightning bolts as the SS
        "\uD83D\uDD95" // middle finger
    )

    private val NAZI_NUMBERS = listOf("1488")

    private val LEET = mapOf(
        '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't',
        '@' to 'a', '$' to 's', '!' to 'i', '|' to 'i', '+' to 't', '\u20AC' to 'e', '\u00A1' to 'i'
    )

    // Cyrillic and Greek letters that look like Latin ones.
    private val LOOKALIKES = mapOf(
        '\u0430' to 'a', '\u0435' to 'e', '\u043E' to 'o', '\u0440' to 'p', '\u0441' to 'c',
        '\u0445' to 'x', '\u0443' to 'y', '\u0456' to 'i', '\u0455' to 's', '\u0458' to 'j',
        '\u043A' to 'k', '\u043C' to 'm', '\u0442' to 't',
        '\u03B1' to 'a', '\u03BF' to 'o', '\u03C1' to 'p', '\u03B5' to 'e', '\u03B9' to 'i',
        '\u03BD' to 'v', '\u03BA' to 'k', '\u03C4' to 't', '\u03C5' to 'u', '\u03C7' to 'x'
    )

    private val NON_LETTERS = Regex("[^a-z]+")
    private val WORD_MATCHER = Regex("^(?:" + alternatives(WORDS) + ")(?:es|s)?$")
    private val SUBSTRING_MATCHER = Regex(alternatives(SUBSTRINGS))

    fun isAllowed(text: String): Boolean {
        if (text.isEmpty()) return true
        if (SYMBOLS.any { text.contains(it) }) return false
        val digits = text.filter { it.isDigit() }
        if (NAZI_NUMBERS.any { digits.contains(it) }) return false

        val folded = fold(text)
        return !containsBad(folded) && !containsBad(folded.map { LEET[it] ?: it }.joinToString(""))
    }

    private fun containsBad(text: String): Boolean {
        val words = text.split(NON_LETTERS).filter { it.isNotEmpty() }
        if (words.any { WORD_MATCHER.matches(it) }) return true
        return SUBSTRING_MATCHER.containsMatchIn(text.filter { it in 'a'..'z' })
    }

    // Lowercase, no accents and with look-alike letters turned into Latin ones.
    private fun fold(text: String): String {
        val prepared = text.lowercase().replace("\u00F1", "ny")
        return Normalizer.normalize(prepared, Normalizer.Form.NFKD)
            .filter { it.category != CharCategory.NON_SPACING_MARK }
            .map { LOOKALIKES[it] ?: it }
            .joinToString("")
    }

    // Each letter may be stretched ("fuuuck"), but a doubled letter in the entry must stay
    // doubled, so "nigger" does not match the country "Niger".
    private fun alternatives(list: String): String = list.trim().split(Regex("\\s+")).joinToString("|") { pattern(it) }

    private fun pattern(entry: String): String {
        val builder = StringBuilder()
        var start = 0
        while (start < entry.length) {
            var end = start
            while (end < entry.length && entry[end] == entry[start]) end++
            builder.append(entry[start])
            val run = end - start
            builder.append(if (run > 1) "{$run,}" else "+")
            start = end
        }
        return builder.toString()
    }
}
