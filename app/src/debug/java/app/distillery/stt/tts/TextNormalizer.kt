// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import app.distillery.stt.tts.NumberWords.cardinal
import app.distillery.stt.tts.NumberWords.number
import app.distillery.stt.tts.NumberWords.ordinal
import app.distillery.stt.tts.NumberWords.year

/**
 * Rewrites English text the way it's read aloud, before Kokoro speaks it:
 * "$1.50" → "one dollar and fifty cents", "Sept. 5" → "September fifth",
 * "3:30 pm" → "three thirty P M", "Dr. Smith" → "Doctor Smith", "50%" →
 * "fifty percent", "5 km" → "five kilometers", and so on.
 *
 * Moonshine's pronunciation step only turns words into sounds; it doesn't
 * expand symbols, abbreviations or formats, so without this "$1" is read as
 * "one" and "Sept." as "sept".
 *
 * The rules run in order, most specific first, so that e.g. a date is read as
 * a date before its parts are read as plain numbers. Each rule is
 * deliberately conservative: when a pattern is ambiguous it's left alone.
 */
object TextNormalizer {

    fun normalize(input: String): String {
        var t = input
            .replace(' ', ' ')
            .replace(' ', ' ')
            .replace('−', '-')
            .replace('–', '-') // en dash, used for ranges
        t = webAddresses(t)
        t = phoneNumbers(t)
        t = currency(t)
        t = percents(t)
        t = times(t)
        t = dates(t)
        t = decades(t)
        t = ordinals(t)
        t = versions(t)
        t = numericSymbols(t)
        t = letterNumbers(t)
        t = units(t)
        t = abbreviations(t)
        t = fractions(t)
        t = ranges(t)
        t = numbers(t)
        t = symbols(t)
        t = romanNumerals(t)
        t = acronyms(t)
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    // ------------------------------------------------------------ helpers

    /** "." if the text after [end] starts a new sentence (end of text, or space then a capital). */
    private fun periodIfSentenceEnds(text: String, end: Int): String {
        if (end >= text.length) return "."
        val rest = text.substring(end)
        val next = rest.trimStart()
        return if (rest.isNotEmpty() && rest[0].isWhitespace() && next.isNotEmpty() && next[0].isUpperCase()) "." else ""
    }

    /** When a match swallowed a final "p.m." style period that also ended the sentence, put it back. */
    private fun keptPeriod(text: String, m: MatchResult): String =
        if (m.value.endsWith(".")) periodIfSentenceEnds(text, m.range.last + 1) else ""

    private fun plural(n: String, singular: String, pluralForm: String): String =
        if (n.trim() == "1" || n.trim() == "1.0") singular else pluralForm

    // ------------------------------------------------- web addresses, email

    private val email = Regex("""\b([\w.+-]+)@([\w-]+(?:\.[\w-]+)+)\b""")
    private val url = Regex("""\bhttps?://([^\s/]+)(/\S*)?""")
    private val www = Regex("""\bwww\.([\w-]+(?:\.[\w-]+)+)\b""")

    private fun spokenHost(host: String) = host.removePrefix("www.").split('.').joinToString(" dot ")

    private fun webAddresses(t: String): String {
        var s = email.replace(t) { m ->
            m.groupValues[1].replace(".", " dot ") + " at " + spokenHost(m.groupValues[2])
        }
        // The path of a link is rarely worth hearing; the site is.
        s = url.replace(s) { m ->
            val trailing = m.value.takeLastWhile { it in ".,;:!?)" }
            spokenHost(m.groupValues[1].trimEnd('.', ',')) + trailing
        }
        s = www.replace(s) { m -> "www dot " + spokenHost(m.groupValues[1]) }
        return s
    }

    // --------------------------------------------------------------- phones

    private val usPhone = Regex("""(?<![\d\w])(\+?1[\s.-]?)?\(?(\d{3})\)?[\s.-]?(\d{3})[\s.-](\d{4})(?!\d)""")
    private val localPhone = Regex("""(?<![\d\w-])(\d{3})-(\d{4})(?![\d-])""")

    private fun phoneNumbers(t: String): String {
        val d = NumberWords::digits
        var s = usPhone.replace(t) { m ->
            val country = if (m.groupValues[1].isNotEmpty()) "one, " else ""
            "$country${d(m.groupValues[2])}, ${d(m.groupValues[3])}, ${d(m.groupValues[4])}"
        }
        s = localPhone.replace(s) { m -> "${d(m.groupValues[1])}, ${d(m.groupValues[2])}" }
        return s
    }

    // ------------------------------------------------------------- currency

    private data class Money(val one: String, val many: String, val minorOne: String?, val minorMany: String?)

    private val moneyBySymbol = mapOf(
        "$" to Money("dollar", "dollars", "cent", "cents"),
        "£" to Money("pound", "pounds", "penny", "pence"),
        "€" to Money("euro", "euros", "cent", "cents"),
        "¥" to Money("yen", "yen", null, null),
    )

    private val scaleWords = mapOf(
        "k" to "thousand", "K" to "thousand", "thousand" to "thousand",
        "m" to "million", "M" to "million", "mn" to "million", "million" to "million",
        "b" to "billion", "B" to "billion", "bn" to "billion", "billion" to "billion",
        "t" to "trillion", "T" to "trillion", "tn" to "trillion", "trillion" to "trillion",
    )

    private val money = Regex(
        """(-)?([$£€¥])\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?:\s?(thousand|million|billion|trillion|bn|mn|tn|[kKmMbBtT])(?![A-Za-z]))?""",
    )

    private fun currency(t: String): String = money.replace(t) { m ->
        val (neg, symbol, whole, fraction, scale) = m.destructured
        val cur = moneyBySymbol.getValue(symbol)
        val sign = if (neg.isNotEmpty()) "minus " else ""
        val wholeNum = whole.replace(",", "").toLong()
        if (scale.isNotEmpty()) {
            val amount = number(if (fraction.isEmpty()) whole else "$whole.$fraction")
            return@replace "$sign$amount ${scaleWords.getValue(scale)} ${cur.many}"
        }
        val minor = when {
            fraction.isEmpty() || cur.minorOne == null -> 0L
            fraction.length == 1 -> fraction.toLong() * 10
            fraction.length == 2 -> fraction.toLong()
            else -> -1L // e.g. $1.999: read the number as written
        }
        if (minor < 0) return@replace "$sign${number("$whole.$fraction")} ${cur.many}"
        val major = "${cardinal(wholeNum)} ${if (wholeNum == 1L) cur.one else cur.many}"
        val cents = if (minor > 0) "${cardinal(minor)} ${if (minor == 1L) cur.minorOne else cur.minorMany}" else ""
        sign + when {
            wholeNum == 0L && minor > 0 -> cents
            minor > 0 -> "$major and $cents"
            else -> major
        }
    }

    // -------------------------------------------------------------- percent

    private val percent = Regex("""(?<![\w.])(-?\d[\d,]*(?:\.\d+)?)\s?%""")

    private fun percents(t: String) = percent.replace(t) { m -> number(m.groupValues[1]) + " percent" }

    // ----------------------------------------------------------------- time

    private val clock = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?::\d{2})?(?:\s?([AaPp])\.?\s?[Mm]\.?(?![A-Za-z]))?(?![\d:])""")
    private val hourAmPm = Regex("""(?<![\d:.])(\d{1,2})\s?([AaPp])\.?\s?[Mm]\.?(?![A-Za-z])""")

    private fun ampm(letter: String) = if (letter.equals("a", true)) " A M" else " P M"

    private fun times(t: String): String {
        var s = clock.replace(t) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val suffix = m.groupValues[3]
            if (h > 24 || min > 59) return@replace m.value
            val minutes = when {
                min == 0 && suffix.isEmpty() && h > 12 -> " hundred"
                min == 0 -> if (suffix.isEmpty()) " o'clock" else ""
                min < 10 -> " oh ${cardinal(min)}"
                else -> " ${cardinal(min)}"
            }
            cardinal(h) + minutes + (if (suffix.isEmpty()) "" else ampm(suffix)) + keptPeriod(t, m)
        }
        s = hourAmPm.replace(s) { m ->
            val h = m.groupValues[1].toInt()
            if (h !in 1..12) m.value else cardinal(h) + ampm(m.groupValues[2]) + keptPeriod(s, m)
        }
        return s
    }

    // ---------------------------------------------------------------- dates

    private val months = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val monthAbbr = mapOf(
        "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "Jun" to 6, "Jul" to 7, "Aug" to 8,
        "Sep" to 9, "Sept" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12,
    )
    private val monthPattern =
        (months + monthAbbr.keys).sortedByDescending { it.length }.joinToString("|")

    private fun monthNumber(name: String): Int =
        months.indexOf(name).takeIf { it >= 0 }?.plus(1) ?: monthAbbr.getValue(name)

    private fun yearWords(y: String): String {
        val n = y.toInt()
        return if (y.length == 2) cardinal(n).let { if (n < 10) "oh $it" else it } else year(n)
    }

    private val monthDayYear = Regex("""\b($monthPattern)\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(\d{4})\b)?""")
    private val dayMonthYear = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($monthPattern)\b\.?(?:,?\s+(\d{4})\b)?""")
    private val monthYear = Regex("""\b($monthPattern)\.?\s+(\d{4})\b""")
    private val numericDate = Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})/(\d{4}|\d{2})(?![\d/])""")
    private val isoDate = Regex("""(?<![\d-])(\d{4})-(\d{2})-(\d{2})(?![\d-])""")
    private val monthAbbrDot = Regex("""\b(Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\.""")
    private val weekdayAbbr = mapOf(
        "Mon" to "Monday", "Tue" to "Tuesday", "Tues" to "Tuesday", "Wed" to "Wednesday",
        "Thu" to "Thursday", "Thur" to "Thursday", "Thurs" to "Thursday", "Fri" to "Friday",
        "Sat" to "Saturday", "Sun" to "Sunday",
    )
    private val weekdayDot = Regex("""\b(Mon|Tues|Tue|Wed|Thurs|Thur|Thu|Fri|Sat|Sun)\.(?=,|\s+(?:$monthPattern|\d))""")

    private fun spokenDate(month: Int, day: Int, yearText: String?): String? {
        if (month !in 1..12 || day !in 1..31) return null
        val base = "${months[month - 1]} ${ordinal(day)}"
        return if (yearText.isNullOrEmpty()) base else "$base, ${yearWords(yearText)}"
    }

    private fun dates(t: String): String {
        var s = isoDate.replace(t) { m ->
            val (y, mo, d) = m.destructured
            spokenDate(mo.toInt(), d.toInt(), y) ?: m.value
        }
        s = numericDate.replace(s) { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val y = m.groupValues[3]
            // US order (month/day) unless the first part can't be a month.
            (if (a <= 12) spokenDate(a, b, y) else spokenDate(b, a, y)) ?: m.value
        }
        s = monthDayYear.replace(s) { m ->
            spokenDate(monthNumber(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3]) ?: m.value
        }
        s = dayMonthYear.replace(s) { m ->
            val day = m.groupValues[1].toInt()
            val month = monthNumber(m.groupValues[2])
            if (day !in 1..31) return@replace m.value
            val y = m.groupValues[3]
            "the ${ordinal(day)} of ${months[month - 1]}" + if (y.isEmpty()) "" else ", ${yearWords(y)}"
        }
        s = monthYear.replace(s) { m -> "${months[monthNumber(m.groupValues[1]) - 1]} ${yearWords(m.groupValues[2])}" }
        s = monthAbbrDot.replace(s) { m ->
            months[monthNumber(m.groupValues[1]) - 1] + periodIfSentenceEnds(s, m.range.last + 1)
        }
        s = weekdayDot.replace(s) { m -> weekdayAbbr.getValue(m.groupValues[1]) }
        return s
    }

    // --------------------------------------------------- decades, ordinals

    private val decade = Regex("""(?<![\w'])(1[1-9]|20)(\d0)s\b""")
    private val shortDecade = Regex("""(?<!\w)['’](\d)0s\b""")

    private fun decades(t: String): String {
        var s = decade.replace(t) { m -> NumberWords.plural(year((m.groupValues[1] + m.groupValues[2]).toInt())) }
        s = shortDecade.replace(s) { m -> NumberWords.plural(cardinal(m.groupValues[1].toInt() * 10)) }
        return s
    }

    private val ordinalNumber = Regex("""(?<![\w.])(\d{1,3}(?:,\d{3})*|\d+)(st|nd|rd|th)\b""")

    private fun ordinals(t: String) = ordinalNumber.replace(t) { m -> ordinal(m.groupValues[1].replace(",", "").toLong()) }

    // ------------------------------------------------------------- versions

    private val version = Regex("""(?<![\w.])(\d+(?:\.\d+){2,})(?![\w.]*\d)""")

    /** "2.0.1" → "two point zero point one". */
    private fun versions(t: String) = version.replace(t) { m ->
        m.groupValues[1].split('.').joinToString(" point ") { NumberWords.number(it) }
    }

    // ---------------------------------------------------------------- units

    private class Unit(val symbol: String, val one: String, val many: String, val attachedOnly: Boolean = false)

    private val unitList = listOf(
        Unit("km/h", "kilometer per hour", "kilometers per hour"),
        Unit("kph", "kilometer per hour", "kilometers per hour"),
        Unit("mph", "mile per hour", "miles per hour"),
        Unit("km", "kilometer", "kilometers"),
        Unit("cm", "centimeter", "centimeters"),
        Unit("mm", "millimeter", "millimeters"),
        Unit("mi", "mile", "miles"),
        Unit("ft", "foot", "feet"),
        Unit("kg", "kilogram", "kilograms"),
        Unit("mg", "milligram", "milligrams"),
        Unit("lbs", "pound", "pounds"),
        Unit("lb", "pound", "pounds"),
        Unit("oz", "ounce", "ounces"),
        Unit("ml", "milliliter", "milliliters"),
        Unit("mL", "milliliter", "milliliters"),
        Unit("TB", "terabyte", "terabytes"),
        Unit("GB", "gigabyte", "gigabytes"),
        Unit("MB", "megabyte", "megabytes"),
        Unit("KB", "kilobyte", "kilobytes"),
        Unit("kB", "kilobyte", "kilobytes"),
        Unit("GHz", "gigahertz", "gigahertz"),
        Unit("MHz", "megahertz", "megahertz"),
        Unit("kHz", "kilohertz", "kilohertz"),
        Unit("Hz", "hertz", "hertz"),
        Unit("Gbps", "gigabit per second", "gigabits per second"),
        Unit("Mbps", "megabit per second", "megabits per second"),
        Unit("kbps", "kilobit per second", "kilobits per second"),
        Unit("kWh", "kilowatt hour", "kilowatt hours"),
        Unit("kW", "kilowatt", "kilowatts"),
        Unit("mAh", "milliamp hour", "milliamp hours"),
        Unit("°F", "degree Fahrenheit", "degrees Fahrenheit"),
        Unit("°C", "degree Celsius", "degrees Celsius"),
        Unit("°", "degree", "degrees"),
        Unit("ms", "millisecond", "milliseconds"),
        Unit("sec", "second", "seconds"),
        Unit("secs", "second", "seconds"),
        Unit("min", "minute", "minutes"),
        Unit("mins", "minute", "minutes"),
        Unit("hrs", "hour", "hours"),
        Unit("hr", "hour", "hours"),
        // Letters that are also words or initials: only when written right
        // against the number ("5m", "3in", "60s" is a decade so not here).
        Unit("m", "meter", "meters", attachedOnly = true),
        Unit("g", "gram", "grams", attachedOnly = true),
        Unit("h", "hour", "hours", attachedOnly = true),
        Unit("in", "inch", "inches", attachedOnly = true),
        Unit("L", "liter", "liters", attachedOnly = true),
        Unit("W", "watt", "watts", attachedOnly = true),
        Unit("V", "volt", "volts", attachedOnly = true),
        Unit("x", "times", "times", attachedOnly = true),
    )
    private val unitsBySymbol = unitList.associateBy { it.symbol }
    private val unitPattern = Regex(
        """(?<![\w./])(\d[\d,]*(?:\.\d+)?)(\s?)(""" +
            unitList.map { it.symbol }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
            """)(?![\w/])""",
    )

    private fun units(t: String) = unitPattern.replace(t) { m ->
        val (amount, space, symbol) = m.destructured
        val unit = unitsBySymbol.getValue(symbol)
        if (unit.attachedOnly && space.isNotEmpty()) return@replace m.value
        number(amount) + " " + plural(amount, unit.one, unit.many)
    }

    // -------------------------------------------------------- abbreviations

    /** Titles: only before a capitalized name, and never the end of a sentence. */
    private val titles = mapOf(
        "Mr" to "Mister", "Mrs" to "Missus", "Ms" to "Miz", "Mx" to "Mix", "Dr" to "Doctor",
        "Prof" to "Professor", "Sen" to "Senator", "Rep" to "Representative", "Gov" to "Governor",
        "Gen" to "General", "Capt" to "Captain", "Lt" to "Lieutenant", "Sgt" to "Sergeant",
        "Col" to "Colonel", "Rev" to "Reverend", "Hon" to "Honorable", "Pres" to "President",
        "Fr" to "Father", "St" to "Saint", "Mt" to "Mount", "Ft" to "Fort",
    )
    private val titleBeforeName = Regex("""\b(?:(Mr|Mrs|Ms|Mx|Dr|St)\.?|(${titles.keys.joinToString("|")})\.)(?=\s+[A-Z])""")

    /** These are only ever titles, whatever follows. */
    private val alwaysTitle = Regex("""\b(Mr|Mrs|Ms|Mx)\.""")

    /** Street words after a name: "Main St." → "Main Street". */
    private val streets = mapOf(
        "St" to "Street", "Ave" to "Avenue", "Blvd" to "Boulevard", "Rd" to "Road", "Dr" to "Drive",
        "Ln" to "Lane", "Hwy" to "Highway", "Pkwy" to "Parkway", "Ct" to "Court", "Pl" to "Place",
    )
    // After a capitalized word, and not before a name ("Main St., " but not "Dr. Smith").
    private val streetAfterName = Regex(
        """(?<=[A-Z][a-z]{1,20}\s)(${streets.keys.joinToString("|")})(\.?)(?=\s*[,;:)]|\s+[a-z\d]|\s*$)""",
    )

    /** Abbreviations that can end a sentence; the period stays when they do. */
    private val words = mapOf(
        "etc" to "et cetera", "approx" to "approximately", "Jr" to "Junior",
        "Sr" to "Senior", "Inc" to "Incorporated", "Ltd" to "Limited", "Corp" to "Corporation",
        "Co" to "Company", "Dept" to "Department", "dept" to "department", "Univ" to "University",
        "Assn" to "Association", "Bros" to "Brothers", "misc" to "miscellaneous", "Apt" to "Apartment",
        "Ste" to "Suite", "Bldg" to "Building", "est" to "estimated", "Est" to "Established",
    )
    private val wordWithDot = Regex("""\b(${words.keys.joinToString("|")})\.""")
    private val vsNoDot = Regex("""\bvs\b""")
    private val vsDot = Regex("""\bvs\.""")

    /** Kitchen units, also after fractions ("3/4 tsp"). */
    private val kitchenUnits = mapOf("tsp" to "teaspoon", "tbsp" to "tablespoon", "Tbsp" to "tablespoon", "tbs" to "tablespoon")
    private val kitchen = Regex("""\b(tsp|tbsp|Tbsp|tbs)\b\.?""")

    /** Only meaningful before a number: "No. 5", "p. 12", "Vol. 3". */
    private val beforeNumber = mapOf(
        "No" to "number", "no" to "number", "Nos" to "numbers", "Vol" to "volume", "vol" to "volume",
        "pp" to "pages", "p" to "page", "Ch" to "chapter", "ch" to "chapter", "Fig" to "figure",
        "fig" to "figure", "Ex" to "example", "Sec" to "section", "Art" to "article", "Rm" to "room",
    )
    private val abbrBeforeNumber = Regex("""\b(${beforeNumber.keys.joinToString("|")})\.\s?(?=\d)""")

    private val latin = listOf(
        Regex("""\be\.\s?g\.(?:,)?""", RegexOption.IGNORE_CASE) to "for example,",
        Regex("""\bi\.\s?e\.(?:,)?""", RegexOption.IGNORE_CASE) to "that is,",
        Regex("""\ba\.k\.a\.?""", RegexOption.IGNORE_CASE) to "also known as",
        Regex("""\bc\.(?=\s?\d{3,4}\b)""") to "circa",
        Regex("""\bw/o\b""") to "without",
        Regex("""\bw/(?=\s)""") to "with",
    )

    /** Dotted initialisms: "U.S." → "U S", "U.K." → "U K". */
    private val dotted = Regex("""\b((?:[A-Z]\.){2,})""")

    private fun abbreviations(t: String): String {
        var s = t
        for ((regex, spoken) in latin) s = regex.replace(s, spoken)
        s = abbrBeforeNumber.replace(s) { m -> beforeNumber.getValue(m.groupValues[1]) + " " }
        s = streetAfterName.replace(s) { m ->
            streets.getValue(m.groupValues[1]) + if (m.groupValues[2].isNotEmpty()) periodIfSentenceEnds(s, m.range.last + 1) else ""
        }
        s = titleBeforeName.replace(s) { m -> titles.getValue(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
        s = alwaysTitle.replace(s) { m -> titles.getValue(m.groupValues[1]) }
        s = vsDot.replace(s, "versus")
        s = kitchen.replace(s) { m -> kitchenUnits.getValue(m.groupValues[1]) }
        s = wordWithDot.replace(s) { m -> words.getValue(m.groupValues[1]) + periodIfSentenceEnds(s, m.range.last + 1) }
        s = vsNoDot.replace(s, "versus")
        s = dotted.replace(s) { m ->
            m.groupValues[1].filter { it.isLetter() }.toList().joinToString(" ") + periodIfSentenceEnds(s, m.range.last + 1)
        }
        return s
    }

    // ---------------------------------------------------- fractions, ranges

    private val vulgar = mapOf(
        '½' to "one half", '⅓' to "one third", '⅔' to "two thirds", '¼' to "one quarter",
        '¾' to "three quarters", '⅛' to "one eighth", '⅜' to "three eighths", '⅝' to "five eighths", '⅞' to "seven eighths",
    )
    private val dateCues = setOf("on", "by", "until", "till", "from", "since", "before", "after", "due", "dated", "through", "thru")
    private val fraction = Regex("""(?<![\d/.])(\d{1,2})/(\d{1,2})(?![\d/])""")

    private fun fractions(t: String): String {
        var s = t
        for ((ch, spoken) in vulgar) s = s.replace(Regex("""(\d)?$ch""")) { m ->
            if (m.groupValues[1].isEmpty()) " $spoken" else "${m.groupValues[1]} and $spoken"
        }
        s = s.replace(Regex("""(?<!\d)24/7(?!\d)"""), "twenty four seven")
        s = fraction.replace(s) { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val before = s.substring(0, m.range.first).trimEnd().substringAfterLast(' ').lowercase()
            when {
                b == 0 -> m.value
                before in dateCues && a in 1..12 && b in 1..31 -> spokenDate(a, b, null) ?: m.value
                a < b && b <= 16 -> {
                    val denom = when (b) {
                        2 -> if (a == 1) "half" else "halves"
                        4 -> if (a == 1) "quarter" else "quarters"
                        else -> ordinal(b) + if (a == 1) "" else "s"
                    }
                    "${cardinal(a)} $denom"
                }
                a in 1..12 && b in 1..31 -> spokenDate(a, b, null) ?: m.value
                else -> "${cardinal(a)} over ${cardinal(b)}"
            }
        }
        return s
    }

    private val range = Regex("""(?<![\w.,-])(\d[\d,]*(?:\.\d+)?)\s?-\s?(\d[\d,]*(?:\.\d+)?)(?![\w-]|\.\d)""")

    private fun ranges(t: String) = range.replace(t) { m -> "${m.groupValues[1]} to ${m.groupValues[2]}" }

    // -------------------------------------------------------------- numbers

    private val numberToken = Regex("""(?<![\w.,])(-)?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?![\d,]*\d)""")

    private fun numbers(t: String) = numberToken.replace(t) { m ->
        val (neg, whole, frac) = m.destructured
        val start = m.range.first
        // A minus sign only counts at the start or after a space or "(".
        val minus = neg.isNotEmpty() && (start == 0 || t[start - 1].isWhitespace() || t[start - 1] == '(')
        val prefix = if (neg.isNotEmpty() && !minus) "-" else ""
        val plain = whole.replace(",", "")
        val spoken = if (frac.isEmpty() && !whole.contains(',') && plain.length == 4 && plain.toInt() in 1100..2099) {
            year(plain.toInt()) // bare four-digit numbers in this range are almost always years
        } else {
            number((if (minus) "-" else "") + whole + if (frac.isEmpty()) "" else ".$frac")
        }
        prefix + spoken
    }

    // ------------------------------------- symbols that go with a number

    private val numericSymbolRules = listOf(
        Regex("""#\s?(?=\d)""") to "number ",
        Regex("""~\s?(?=\d)""") to "about ",
        Regex("""(?<=\d)\s?\+\s?(?=\d)""") to " plus ",
        Regex("""(?<=\d)\s?[×]\s?(?=\d)""") to " by ",
        Regex("""(?<=\d)x(?=\d)""") to " by ",
        Regex("""(?<=\d):(?=\d)""") to " to ", // ratios left after times: 16:9
    )

    private fun numericSymbols(t: String): String {
        var s = t
        for ((regex, spoken) in numericSymbolRules) s = regex.replace(s, spoken)
        return s
    }

    /** "Q3" → "Q three", "F1" → "F one". */
    private val letterNumber = Regex("""\b([A-Z])(\d{1,2})\b""")

    private fun letterNumbers(t: String) = letterNumber.replace(t) { m -> "${m.groupValues[1]} ${cardinal(m.groupValues[2].toInt())}" }

    // -------------------------------------------------------------- symbols

    private val symbolRules = listOf(
        Regex("""\s*&\s*""") to " and ",
        Regex("""(?<=\d)\s?\+\s?(?=\d)""") to " plus ",
        Regex("""(?<=\s)\+(?=\s)""") to "plus",
        Regex("""(?<=\s)=(?=\s)""") to "equals",
        Regex("""(?<=\d)\s?[×x]\s?(?=\d)""") to " by ",
        Regex("""÷""") to " divided by ",
        Regex("""#(?=\d)""") to "number ",
        Regex("""(?<![\w#])#(?=[A-Za-z])""") to "hashtag ",
        Regex("""~(?=\s?\d)""") to "about ",
        Regex("""(?<=[A-Za-z])/(?=[A-Za-z])""") to " slash ",
        Regex("""\s@\s""") to " at ",
        Regex("""%""") to " percent",
        Regex("""°""") to " degrees",
        Regex("""[•·▪]""") to ", ",
    )

    private fun symbols(t: String): String {
        var s = t
        for ((regex, spoken) in symbolRules) s = regex.replace(s, spoken)
        return s
    }

    // ------------------------------------------------------- roman numerals

    private val romanValues = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50)

    private fun roman(r: String): Int? {
        var total = 0
        for (i in r.indices) {
            val v = romanValues[r[i]] ?: return null
            val next = r.getOrNull(i + 1)?.let { romanValues[it] } ?: 0
            total += if (v < next) -v else v
        }
        return total.takeIf { it in 1..60 }
    }

    /** "World War II" → "World War two", "Chapter IV" → "Chapter four". */
    private val romanAfterNoun = Regex("""\b(Part|Chapter|Volume|Book|Act|Phase|Season|War|Episode|Title|Level|Stage|Type|Class|Grade|Category|Article|Section)\s+([IVXL]{1,6})\b""")

    /** "Henry VIII" → "Henry the eighth"; not "I", which is a word. */
    private val romanAfterName = Regex("""\b([A-Z][a-z]+)\s+(II|III|IV|VI|VII|VIII|IX|XI|XII|XIII|XIV|XV|XVI)\b""")

    private fun romanNumerals(t: String): String {
        var s = romanAfterNoun.replace(t) { m ->
            roman(m.groupValues[2])?.let { "${m.groupValues[1]} ${cardinal(it)}" } ?: m.value
        }
        s = romanAfterName.replace(s) { m ->
            roman(m.groupValues[2])?.let { "${m.groupValues[1]} the ${ordinal(it)}" } ?: m.value
        }
        return s
    }

    // ------------------------------------------------------------- acronyms

    /** Read as words, though they're capitals. */
    private val asWords = mapOf(
        "OK" to "okay", "NASA" to "NASA", "NATO" to "NATO", "ASAP" to "A S A P", "GIF" to "gif",
        "PIN" to "pin", "RAM" to "ram", "LOL" to "L O L", "SIM" to "sim", "WiFi" to "wifi",
    )

    /** Spelled out even though they have vowels. */
    private val spelled = setOf(
        "US", "USA", "UK", "EU", "UN", "AI", "CEO", "CFO", "CTO", "COO", "IOU", "UFO", "UAE", "ID", "IP",
        "OS", "UI", "UX", "API", "URL", "PDF", "FAQ", "ETA", "EV", "IQ", "IRS", "ICU", "ER", "AP", "IOS",
        "EST", "EDT", "PST", "PDT", "UTC", "GMT", "AM", "PM",
    )
    private val capsWord = Regex("""\b[A-Z]{2,5}\b""")

    private fun acronyms(t: String): String {
        val letters = t.filter { it.isLetter() }
        // In text that's shouting in capitals, capital words are just words.
        if (letters.length > 12 && letters.count { it.isUpperCase() } > letters.length * 0.6) return t
        return capsWord.replace(t) { m ->
            val w = m.value
            when {
                w in asWords -> asWords.getValue(w)
                // Short capital words are nearly always initials (FBI, CIA, ATM).
                w in spelled || w.length <= 3 || w.none { it in "AEIOU" } -> w.toList().joinToString(" ")
                else -> w
            }
        }
    }
}
