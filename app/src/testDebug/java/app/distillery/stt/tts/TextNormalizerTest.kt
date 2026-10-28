// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    private val cases = listOf(
        "It costs \$1 today." to
            "It costs one dollar today.",
        "It costs \$1.50, or \$0.99 on sale, or \$2.5 million." to
            "It costs one dollar and fifty cents, or ninety nine cents on sale, or two point five million dollars.",
        "We raised £20 and €3.05." to
            "We raised twenty pounds and three euros and five cents.",
        "Meet me Sept. 5, 2026 at 3:30 pm." to
            "Meet me September fifth, twenty twenty six at three thirty P M.",
        "The party is on Sept. 21st. Bring snacks." to
            "The party is on September twenty first. Bring snacks.",
        "Due 10/28/2026 or 2026-10-28." to
            "Due October twenty eighth, twenty twenty six or October twenty eighth, twenty twenty six.",
        "Dr. Smith lives at 42 Main St. near the park." to
            "Doctor Smith lives at forty two Main Street near the park.",
        "Mr. and Mrs. Jones went to St. Louis." to
            "Mister and Missus Jones went to Saint Louis.",
        "She finished 1st, he was 22nd, and I was 103rd." to
            "She finished first, he was twenty second, and I was one hundred third.",
        "Sales rose 50% in the 1990s and the '80s." to
            "Sales rose fifty percent in the nineteen nineties and the eighties.",
        "It's 72°F outside and 5 km away, about 3.5 mi." to
            "It's seventy two degrees Fahrenheit outside and five kilometers away, about three point five miles.",
        "Call (555) 123-4567 or 555-9876." to
            "Call five five five, one two three, four five six seven or five five five, nine eight seven six.",
        "In 1999 there were 1,234,567 people, and in 2005 about 2,000." to
            "In nineteen ninety nine there were one million two hundred thirty four thousand five hundred sixty seven people, and in two thousand five about two thousand.",
        "Pages 5-10 cover chapters 3–4." to
            "Pages five to ten cover chapters three to four.",
        "Use 1/2 cup of sugar and 3/4 tsp of salt on 5/12." to
            "Use one half cup of sugar and three quarters teaspoon of salt on May twelfth.",
        "The FBI and NASA met the CEO of the USA, e.g. at the UN." to
            "The F B I and NASA met the C E O of the U S A, for example, at the U N.",
        "Email me at john.doe@example.com or visit https://www.example.com/page." to
            "Email me at john dot doe at example dot com or visit example dot com.",
        "World War II ended. Henry VIII had six wives." to
            "World War two ended. Henry the eighth had six wives.",
        "Temperatures of -5 degrees, 24/7 service, AT&T vs. Verizon, etc. Then we left." to
            "Temperatures of minus five degrees, twenty four seven service, A T and T versus Verizon, et cetera. Then we left.",
        "The meeting is at 9am, ends at 5 PM, or 17:00." to
            "The meeting is at nine A M, ends at five P M, or seventeen hundred.",
        "The score was 3-2 and COVID-19 spread." to
            "The score was three to two and COVID-nineteen spread.",
        "Released Jan. 2020; version 2.0.1 costs \$5k." to
            "Released January twenty twenty; version two point zero point one costs five thousand dollars.",
        "I have 2 cats and 3 dogs, and it's 5 o'clock." to
            "I have two cats and three dogs, and it's five o'clock.",
        "Room 101 is on floor 3. See No. 5 on p. 12." to
            "Room one hundred one is on floor three. See number five on page twelve.",
        "It's 100% true! I am at home. Is it in the box?" to
            "It's one hundred percent true! I am at home. Is it in the box?",
        "Happy St. Patrick's Day, Mrs. Doubtfire." to
            "Happy Saint Patrick's Day, Missus Doubtfire.",
        "The U.S. economy grew 2.3% in Q3." to
            "The U S economy grew two point three percent in Q three.",
        "THIS IS ALL CAPS AND VERY LOUD TEXT HERE." to
            "THIS IS ALL CAPS AND VERY LOUD TEXT HERE.",
        "We left at 6 a.m. The sun rose." to
            "We left at six A M. The sun rose.",
        "He scored 3 goals in 90 minutes on 10/5." to
            "He scored three goals in ninety minutes on October fifth.",
        "Download 2 GB at 100 Mbps with 8GB RAM." to
            "Download two gigabytes at one hundred megabits per second with eight gigabytes ram.",
        "The ratio is 16:9 and verse 3:16." to
            "The ratio is sixteen to nine and verse three sixteen.",
        "Born in 1850, died in 1920; the 1800s." to
            "Born in eighteen fifty, died in nineteen twenty; the eighteen hundreds.",
        "Order #42 now, call 1-800-555-1234." to
            "Order number forty two now, call one, eight zero zero, five five five, one two three four.",
    )

    @Test fun readsTextTheWayItIsSpoken() {
        for ((input, expected) in cases) assertEquals(input, expected, TextNormalizer.normalize(input))
    }

    @Test fun numberWords() {
        assertEquals("twenty one", NumberWords.cardinal(21))
        assertEquals("one thousand two hundred thirty four", NumberWords.cardinal(1234))
        assertEquals("twenty first", NumberWords.ordinal(21))
        assertEquals("twelfth", NumberWords.ordinal(12))
        assertEquals("one hundredth", NumberWords.ordinal(100))
        assertEquals("nineteen oh five", NumberWords.year(1905))
        assertEquals("two thousand", NumberWords.year(2000))
        assertEquals("twenty ten", NumberWords.year(2010))
        assertEquals("zero zero seven", NumberWords.number("007"))
    }

    @Test fun leavesPlainTextAlone() {
        val plain = "Nothing to change here, just words and a question?"
        assertEquals(plain, TextNormalizer.normalize(plain))
    }
}
