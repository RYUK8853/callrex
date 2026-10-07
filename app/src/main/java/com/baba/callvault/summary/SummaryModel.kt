/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.transcription.model.DownloadableModel

/**
 * The language models CallVault will summarise with. Currently exactly one.
 *
 * Chosen by measurement on 2026-08-21, not by reputation. Gemma 4 E2B was the only candidate that
 * **invented nothing** across four controlled runs — it kept a delivery window at nine-to-eleven
 * where Qwen3.5-4B narrowed it to nine-to-ten, and got every figure in the sample right. For a
 * record of a real conversation a plausible fabrication is the worst possible output, so
 * faithfulness is the one property that cannot be traded away for speed or size. Qwen was also
 * roughly twice as slow on identical work and spent a third of its budget thinking out loud.
 *
 * **Never bundled, always downloaded.** Two independent reasons point the same way. It is 3.46 GB,
 * which is not something to put in an APK. (An earlier version of this comment also said it ships
 * under Google's *Gemma Terms of Use*; that is **wrong** for Gemma 4, which is **Apache-2.0 and
 * ungated** — checked against the model card on 2026-08-26. The licence was never the objection; the
 * 3.46 GB was, and still is.) Downloading keeps the app's
 * own licensing clean and leaves the user accepting Google's terms directly, from Google's own
 * distribution.
 */
enum class SummaryModel(
    override val id: String,
    override val fileName: String,
    override val url: String,
    override val sha256: String,
    override val sizeBytes: Long,
    val peakMemoryBytes: Long
) : DownloadableModel {

    /**
     * Gemma 4 E2B instruction-tuned, quantisation-**aware**-trained, Q4_K_XL.
     *
     * The same model as before, quantised a better way. The previous build was post-hoc Q4_K_M: train
     * at full precision, then squash. This one is Google's QAT release — the squashing is part of
     * training, so the weights are chosen knowing they will end up at int4 rather than being rounded
     * into it afterwards. Same architecture, same licence, **2.62 GB against 3.46 GB**: 842 MB less to
     * download and to keep, on a feature whose single biggest cost to the user is the download.
     *
     * Still Q4_K, deliberately. That family has the repacked ARM GEMM kernels with an i8mm path; the
     * Q5_0/Q5_1 family does not, so a nominally finer quantisation would run slower on the phones this
     * is for. Verified 2026-08-27 from the Hugging Face API: ungated, `license:apache-2.0`,
     * 2,620,370,976 bytes, sha256 `e5310072…`.
     *
     * `peakMemoryBytes` is NOT reduced by the same amount and is deliberately left where it is: llama
     * repacks weights into anonymous memory at load, so the resident cost is not simply the file size.
     * Lowering this on arithmetic would risk admitting the model onto a phone that cannot hold it.
     */
    GEMMA_4_E2B_QAT_Q4_K_XL(
        id = "gemma-4-e2b-it-qat-q4_k_xl",
        fileName = "gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf",
        url = "https://huggingface.co/unsloth/gemma-4-E2B-it-qat-GGUF/resolve/main/" +
            "gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf",
        sha256 = "e531007218dfab990486a5de7676a6932d6ea8dea233d1f698d7c21cf8a16889",
        sizeBytes = 2_620_370_976L,
        peakMemoryBytes = 3_500_000_000L
    );

    companion object {

        /** The model used when the user has expressed no preference. There is only one. */
        val DEFAULT = GEMMA_4_E2B_QAT_Q4_K_XL

        fun fromId(id: String?): SummaryModel? = entries.firstOrNull { it.id == id }
    }
}
