/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * The three whisper decoding knobs that were left at a default nobody chose.
 *
 * Gathered into one value rather than passed as three loose ints so that the benchmark and the app
 * take **the same code path** — a measurement made through a second, simpler path measures the
 * second path. [DEFAULT] is what ships; the instrumented benchmark constructs variants of it.
 *
 * Immutable, like everything else here: a run's settings must still read the same afterwards as
 * they did going in, or the number written next to them is not the number that produced them.
 *
 * **[DEFAULT] is VAD on, greedy decoding.** Measured end to end on the OP9 Pro with
 * `large-v3-turbo-q8_0`, on two real Hebrew calls rather than one. The transcripts are the evidence
 * rather than a WER score, because there is no reference transcript to score against.
 *
 * The two calls disagreed, and that disagreement is the most useful thing here. On a 4:05
 * negotiation, beam-5 repaired an inverted negation and cost 5% wall-clock. On an 8:46 support
 * escalation that opens with music, beam-5 **combined with VAD** deleted the first 94 seconds of
 * the call outright. VAD helped on both. So VAD ships and beam does not: see [beamSize] for the
 * full 2×2 and the mechanism, which is an interaction between the two knobs and not a property of
 * either alone.
 *
 * The lesson worth keeping when the next knob is proposed: **a single call cannot clear a decoding
 * change.** The 4:05 call would have shipped beam-5 unopposed, and it is the knob that loses whole
 * minutes of speech on other audio.
 */
data class DecodeSettings(
    /**
     * Beam width, or 1 for greedy.
     *
     * MEASURED (Whisper paper arXiv:2212.04356, Appendix D, `large-v2`): beam-5 improves twelve of
     * fourteen sets — CallHome 17.6 → 16.4 WER (−6.8% rel), CORAAL 16.2 → 14.2 (−12.3% rel) — and
     * telephone corpora are precisely our domain. It makes the two noisiest far-field sets worse
     * (AMI-SDM1 36.4 → 39.9), and arXiv:2501.11378 finds higher beam widths hallucinate **more** on
     * non-speech, which is why this must never be raised without [useVad] on to remove the
     * non-speech first.
     *
     * Cost is decoder-side only, and turbo is 32 encoder layers against 4 decoder layers, so the
     * folklore "beam-5 is 3–5× slower" does not describe this model.
     *
     * MEASURED on a real 4:05 Hebrew call, OP9 Pro, `large-v3-turbo-q8_0`, VAD on for both:
     * greedy 341 s, **beam-5 358 s — +5.0%**, for identical segment counts. Not 3×, not even 1.1×.
     * The architectural argument was right, and the folklore is describing a model whose decoder is
     * not four layers deep.
     *
     * What the 5% bought on that call: "את לא הגדרנו" → "עוד לא הגדרנו" (negation restored — greedy
     * with VAD had inverted the sentence), one of the two spoken "80 אלף" figures recovered from
     * "8 אלף", and a coherent sign-off where greedy produced "שכף, ביי". Against that it spelled
     * "מרובים" as "מרוביים" and "ימין" as "ידמין".
     *
     * **That was one call, and a second call reversed the verdict. This is 1, not 5.**
     *
     * MEASURED on a real 8:46 Hebrew call (an angry two-party support escalation, with music on the
     * line at the open), OP9 Pro, `large-v3-turbo-q8_0`, full 2×2 against [useVad]:
     *
     * | variant | wall | segments | chars | chars in first 94 s |
     * |---|---|---|---|---|
     * | greedy, no VAD  | 725 s | 297 | 4692 | 1008 |
     * | greedy + VAD    | **763 s** | 207 | **5970** | **1117** |
     * | beam-5, no VAD  | 852 s | 276 | 5448 | 1017 |
     * | beam-5 + VAD    | 846 s | 117 | 5022 | **21** |
     *
     * Beam-5 **with VAD on** emitted `*ערבית*` — a language tag, not a transcription — at 0 s, 34 s
     * and 64 s, three consecutive 30-second windows, and **the first 94 seconds of the call are
     * simply gone**: the opening of the dispute, which all three other variants transcribe. It then
     * produced a 49-fold `כאילו` repetition loop at 500 s. Discount the language tags and the loop
     * and it nets 4716 usable characters against greedy-plus-VAD's 5963 — worse than the greedy,
     * no-VAD baseline it was supposed to improve, while costing 11% more wall-clock.
     *
     * The mechanism is why this is a *pair* effect and not a beam effect: VAD concatenates the kept
     * speech, packing the opening music hard against the first words; beam-5 makes the resulting
     * wrong hypothesis more confident than greedy would; and rolling conditioning ([maxTextCtx],
     * left at whisper's own 224) then re-injects `*ערבית*` as context and locks it in window after
     * window. Beam-5 *without* VAD did none of this on the same audio. This is exactly the
     * interaction arXiv:2501.11378 warns about (higher beam widths hallucinate more on non-speech),
     * arriving from the opposite direction to the one predicted: VAD did not make beam safe.
     *
     * So the honest reading of two calls is that beam-5 is **audio-dependent** — worth a few
     * repaired words on a clean negotiation, and catastrophic on a call that opens with music. A
     * knob whose downside is silently deleting the first ninety seconds does not ship on the
     * strength of a handful of recovered words. Greedy until there is a corpus, not a call.
     */
    val beamSize: Int = 1,

    /**
     * Cap on the previous windows' text re-injected as conditioning. 0 disables it entirely; -1
     * leaves whisper's own default in place, which is what the benchmark's baseline needs.
     *
     * `no_context = true` does **not** turn this off — it clears carry-over from a previous *call*,
     * once. Within a run `prompt_past` is rebuilt after every 30-second window and re-injected,
     * gated only on this number. Rolling conditioning is the documented mechanism behind repetition
     * loops, and also the only thing holding proper nouns, punctuation and casing steady across a
     * window boundary, so this is a trade in both directions.
     *
     * The nominal whisper.cpp default is 16384, but it is clamped by
     * `min(n_max_text_ctx, n_text_ctx / 2)` and `n_text_ctx` is 448 on every large model — so the
     * **default has always meant 224 tokens, not 16384**. The research note's premise that we run
     * "with full rolling conditioning" overstates it; the window was never unbounded.
     *
     * MEASURED on a real 4:05 Hebrew call, OP9 Pro, `large-v3-turbo-q8_0`, VAD and beam-5 held on:
     *
     * | `n_max_text_ctx` | wall | segments | chars |
     * |---|---|---|---|
     * | 0 (off)        | 332 s | **79** | **2475** |
     * | 64 (upstream's middle ground) | **310 s** | 42 | 2662 |
     * | default (224)  | 358 s | 47 | **2749** |
     *
     * **0 is disqualified, and not marginally.** It fragmented the call into 79 segments of one to
     * two seconds — "אתה רוצה פה" / "אבטחה על הדבר הזה" / "שיהיה לך שימוש" as three separate
     * segments — which is far below the 8–9-word length where downstream quality peaks, and it
     * dropped terminal punctuation almost everywhere. It also produced the *least* text of any
     * variant. That is the documented cost of losing rolling conditioning, and it showed up exactly
     * as predicted.
     *
     * **64 is fast and tidy but loses speech**, which is why it is not what ships: it was the only
     * variant to drop two real utterances the other four all captured — "פתאום 80 אלף" and
     * "יש לך תקציב, יש לך שיר". Dropped speech is invisible in a segment count and unrecoverable
     * downstream, so it outweighs the 13% it saves and the handful of words it gets right.
     *
     * So: leave whisper's own cap alone. Worth revisiting with a second call — 64's speed and its
     * longer segments are real — but not on one call's evidence when that call lost content.
     */
    val maxTextCtx: Int = -1,

    /**
     * Whether to trim non-speech with the bundled Silero model before decoding.
     *
     * MEASURED: Whisper paper Table 7, 10.6 → 10.2 avg WER (−3.8% rel) helping all seven datasets;
     * WhisperX (arXiv:2303.00747) TED-LIUM 10.5 → 9.7 with 5-gram repetitions 131 → 75 (−43%);
     * arXiv:2501.11378 measures a 40.3% non-speech hallucination rate without it. Careless Whisper
     * (arXiv:2402.08021) traces hallucinated phrases to **long non-vocal durations**, which is the
     * defining profile of a phone call.
     *
     * MEASURED on a real 4:05 Hebrew call, OP9 Pro, `large-v3-turbo-q8_0`, greedy for both: 54
     * segments / 2645 chars without, **47 segments / 2706 chars with**. Fewer segments and *more*
     * text at once — so the trimming merged fragments rather than deleting speech, which is the
     * result that had to hold before any of this could ship. The VAD kept 14 speech stretches out
     * of 245 seconds of audio.
     *
     * The single clearest win was a recovered utterance: "אני רוצה לעשות פיצ'ר אחד לראות איך אתה
     * עובד, לתמחיר אותו" — the buyer stating what he actually wants, the crux of the negotiation —
     * which is **absent entirely** from the no-VAD transcript. It also turned "ושנח זור אליך" into
     * "בסדר, אני אחזור אליך". It is not free: it misread both spoken "80 אלף" figures as "8 אלף"
     * on this call, which beam search then partly undid.
     *
     * CONFIRMED on a second call, a real 8:46 Hebrew support escalation: 297 segments / 4692 chars
     * without, **207 segments / 5970 chars with** — the same shape of result, fewer segments and
     * 27% more text. It also repaired two repetition loops the no-VAD run fell into: five identical
     * copies of "וגם את האינטרנט שלך שאני פרגי תנו" at 55–65 s became real speech
     * ("ואגף החינוך בראשות שולח אותי אליך"), and a four-fold "ושע מנהל" became
     * "רק שגם למהל הזה יש מנהל. וגם לו, יש מנהל."
     *
     * **The one cost, and it is the one to watch:** VAD alone dropped a short greeting exchange at
     * 143–150 s — "שלום" and "אהלה, נעים מאוד" — which both no-VAD runs captured. Every other
     * apparent gap in the VAD transcript was a *merge*, with the surrounding long segment carrying
     * the words; this one was a deletion. That is the documented failure mode surviving even at the
     * 100 ms `min_speech_duration_ms` this project already lowered it to, and it is a warning for
     * languages nobody here reads: a missing "enchanté" or a missing backchannel looks like nothing
     * at all in a segment count, and a tester who does not know what was said will not report it.
     * One short social turn per nine minutes is worth 27% more text and two repaired loops — but if this is
     * ever revisited, that is the number to try to move.
     *
     * Off only for measurement. There is no user-facing setting: nothing here is a preference.
     */
    val useVad: Boolean = true,
) {
    init {
        require(beamSize >= 1) { "beamSize must be >= 1, was $beamSize" }
        // -1 is "leave whisper's default alone" and is distinct from 0, which is "no conditioning
        // at all". Anything below -1 would be silently reinterpreted by the native side.
        require(maxTextCtx >= -1) { "maxTextCtx must be >= -1, was $maxTextCtx" }
    }

    /** Compact one-line form for logs, so a transcript in a bug report says how it was produced. */
    override fun toString(): String =
        "beam=$beamSize ctx=$maxTextCtx vad=${if (useVad) "on" else "off"}"

    companion object {
        /** What ships. Every field's justification is on the field. */
        val DEFAULT = DecodeSettings()
    }
}
