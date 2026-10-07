/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry

/**
 * Invented phone calls, for measuring summarisation without touching a real one.
 *
 * Two reasons these are made up rather than read from the phone.
 *
 * The practical one: an isolated instrumented build has its own applicationId and therefore its own
 * empty database, so the real transcripts are not reachable from here — and the alternative,
 * installing over the release build, would drop WRITE_SECURE_SETTINGS and stop the phone recording.
 *
 * The better one: **the facts below are known**. Whether a model invented something is the hardest
 * thing to judge in someone else's private conversation and the easiest thing to check in a call
 * that was written on purpose. Each sample lists exactly what is true; anything else in a summary is
 * a fabrication, and that is the single quality bar this feature has to clear.
 *
 * Quality on the maintainer's own calls stays the maintainer's judgement. These answer the questions
 * a machine can answer: does it reply in the right language, does it invent, and what does it cost.
 */
object SummarySamples {

    /**
     * @param facts everything true in this call. A summary containing anything not on this list has
     *   invented it.
     */
    data class Sample(
        val name: String,
        val language: String,
        val lines: List<Pair<String, String>>,
        val facts: List<String>
    ) {
        fun asSegments(): List<TranscriptSegmentEntry> =
            lines.mapIndexed { index, (speaker, text) ->
                TranscriptSegmentEntry(
                    id = index.toLong(),
                    displayName = name,
                    startMs = index * 4_000L,
                    endMs = (index + 1) * 4_000L,
                    text = text,
                    speaker = speaker
                )
            }
    }

    /** A delivery being rescheduled, in Hebrew. The hard case: RTL, and not a language these models lead on. */
    val hebrew = Sample(
        name = "sample-he",
        language = "he",
        lines = listOf(
            "דני" to "היי, אני מתקשר בקשר למשלוח של המקרר.",
            "מיכל" to "כן, יש לי את ההזמנה מולי. זה לרחוב הרצל 14?",
            "דני" to "נכון. אבל אני לא אהיה בבית ביום שלישי.",
            "מיכל" to "אין בעיה. אפשר להזיז את זה ליום חמישי בבוקר.",
            "דני" to "חמישי בבוקר זה מצוין. באיזו שעה בערך?",
            "מיכל" to "בין תשע לאחת עשרה. הנהג יתקשר עשרים דקות לפני.",
            "דני" to "ומה עם התשלום? אמרתם אלף ומאתיים שקל.",
            "מיכל" to "אלף ומאתיים, כולל הובלה. אפשר לשלם לנהג בכרטיס.",
            "דני" to "מעולה. וההתקנה כלולה?",
            "מיכל" to "ההתקנה עולה מאתיים שקל בנפרד. רוצה שאוסיף?",
            "דני" to "כן, תוסיפי בבקשה.",
            "מיכל" to "הוספתי. אז חמישי בבוקר, אלף וארבע מאות סך הכל.",
            "דני" to "תודה רבה, ביי.",
            "מיכל" to "יום טוב."
        ),
        facts = listOf(
            "the call is about delivering a refrigerator",
            "the address is Herzl Street 14",
            "delivery moves from Tuesday to Thursday morning",
            "the window is between nine and eleven",
            "the driver will call twenty minutes ahead",
            "the price is 1200 shekels including delivery",
            "installation costs 200 shekels extra and was added",
            "the new total is 1400 shekels",
            "payment can be made to the driver by card"
        )
    )

    /** The same shape in English, so a failure can be attributed to the language rather than the task. */
    val english = Sample(
        name = "sample-en",
        language = "en",
        lines = listOf(
            "Sam" to "Hi, I'm calling about the boiler service.",
            "Alex" to "Of course. That's the one booked for the twelfth?",
            "Sam" to "Yes, but I have to be at the hospital that morning.",
            "Alex" to "We can move it. How is Friday the fifteenth?",
            "Sam" to "Friday works if it's after two.",
            "Alex" to "Two to four on Friday the fifteenth. The engineer is Mark.",
            "Sam" to "Is it still ninety pounds?",
            "Alex" to "Ninety for the service. Parts are extra if anything needs replacing.",
            "Sam" to "Last time you mentioned the pressure valve.",
            "Alex" to "It's on the notes. If it needs doing it's another forty.",
            "Sam" to "Go ahead if it needs it, but call me first.",
            "Alex" to "I've put that on the job. Mark will ring before starting.",
            "Sam" to "Great, thanks.",
            "Alex" to "See you Friday."
        ),
        facts = listOf(
            "the call is about a boiler service",
            "the appointment moves from the twelfth to Friday the fifteenth",
            "the slot is two to four in the afternoon",
            "the engineer is called Mark",
            "the service costs ninety pounds",
            "a pressure valve may need replacing, at forty more",
            "the customer wants to be called before any extra work",
            "Mark will ring before starting"
        )
    )

    val all = listOf(hebrew, english)
}
