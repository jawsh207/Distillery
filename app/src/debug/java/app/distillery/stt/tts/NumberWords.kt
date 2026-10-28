// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

/** English number words: cardinals, ordinals, years, decimals and digit strings. */
object NumberWords {

    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf(
        1_000_000_000_000L to "trillion",
        1_000_000_000L to "billion",
        1_000_000L to "million",
        1_000L to "thousand",
    )

    /** 1234 → "one thousand two hundred thirty four". Hyphens are left out on purpose. */
    fun cardinal(n: Long): String {
        if (n < 0) return "minus " + cardinal(-n)
        if (n < 20) return ones[n.toInt()]
        if (n < 100) return tens[(n / 10).toInt()] + if (n % 10 != 0L) " " + ones[(n % 10).toInt()] else ""
        if (n < 1000) return ones[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + cardinal(n % 100) else ""
        for ((value, name) in scales) {
            if (n >= value) return cardinal(n / value) + " " + name + if (n % value != 0L) " " + cardinal(n % value) else ""
        }
        error("unreachable")
    }

    fun cardinal(n: Int): String = cardinal(n.toLong())

    /** 21 → "twenty first". */
    fun ordinal(n: Long): String {
        val words = cardinal(n)
        val head = words.substringBeforeLast(' ', "")
        val last = words.substringAfterLast(' ')
        val ord = when (last) {
            "zero" -> "zeroth"
            "one" -> "first"
            "two" -> "second"
            "three" -> "third"
            "five" -> "fifth"
            "eight" -> "eighth"
            "nine" -> "ninth"
            "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        }
        return if (head.isEmpty()) ord else "$head $ord"
    }

    fun ordinal(n: Int): String = ordinal(n.toLong())

    /** 1999 → "nineteen ninety nine", 1905 → "nineteen oh five", 2005 → "two thousand five", 2026 → "twenty twenty six". */
    fun year(n: Int): String {
        if (n !in 1000..2999) return cardinal(n)
        if (n in 2000..2009) return cardinal(n)
        val hi = n / 100
        val lo = n % 100
        return when {
            lo == 0 -> cardinal(hi) + " hundred"
            lo < 10 -> cardinal(hi) + " oh " + ones[lo]
            else -> cardinal(hi) + " " + cardinal(lo)
        }
    }

    /** "0451" → "zero four five one". */
    fun digits(s: String): String = s.filter { it.isDigit() }.map { ones[it - '0'] }.joinToString(" ")

    /** Plural of a number word: "twenty" → "twenties", "six" → "sixes", "nineteen ninety" → "nineteen nineties". */
    fun plural(words: String): String = when {
        words.endsWith("y") -> words.dropLast(1) + "ies"
        words.endsWith("x") || words.endsWith("s") -> words + "es"
        else -> words + "s"
    }

    /**
     * A written number: "1,234", "3.14", "-5", "007". Grouped digits must use
     * commas every three places; anything else that's long or starts with 0 is
     * read digit by digit.
     */
    fun number(text: String): String {
        var s = text.trim()
        var sign = ""
        if (s.startsWith("-") || s.startsWith("−")) {
            sign = "minus "
            s = s.substring(1)
        }
        val intPart = s.substringBefore('.')
        val fraction = s.substringAfter('.', "")
        val plain = intPart.replace(",", "")
        if (plain.isEmpty() || plain.any { !it.isDigit() }) return text
        val whole = when {
            plain.length > 1 && plain.startsWith("0") && fraction.isEmpty() -> digits(plain)
            plain.length > 15 -> digits(plain)
            else -> cardinal(plain.toLong())
        }
        return sign + if (fraction.isEmpty()) whole else "$whole point ${digits(fraction)}"
    }
}
