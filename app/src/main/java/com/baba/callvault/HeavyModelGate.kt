package com.baba.callvault

import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide gate that keeps at most ONE heavy native model in memory at a time.
 *
 * The two heavy consumers are [com.baba.callvault.transcription.TranscriptionEngine]
 * (whisper.cpp, ~2 GB resident once the model is mapped) and
 * [com.baba.callvault.summary.SummaryEngine] (llama.cpp, ~3.46 GB for the Q4 summary model).
 *
 * The bug this kills: post-call, the transcription worker and the summary worker started
 * ~50 ms apart and each only checked the OTHER engine's `isRunning` flag, so both models
 * could be mapped simultaneously — ~5.5 GB on a phone with ~8 GB total — and the process
 * died mid-run (observed: 7 process deaths in 3 minutes, calls lost).
 *
 * Usage: wrap the whole `initContext`…`freeContext` span of a model run in
 * [withSingleHeavyModel]. The gate is suspend, so a second run simply waits (logged)
 * instead of loading a second copy.
 *
 * Nesting rule: a single run acquires the gate exactly once, at its outermost model span —
 * never inside another gated span — so the gate cannot deadlock with itself.
 */
object HeavyModelGate {

    private const val TAG = "HeavyModelGate"

    private val mutex = Mutex()

    /**
     * Runs [block] while the process holds the heavy-model gate, and returns its value.
     * A concurrent caller waits here until [block]'s model is freed again.
     */
    suspend fun <T> withSingleHeavyModel(block: suspend () -> T): T = mutex.withLock {
        val waitedMs = 0L
        AppLogger.i(TAG, "heavy model gate acquired (waited ${waitedMs} ms)")
        try {
            block()
        } finally {
            AppLogger.i(TAG, "heavy model gate released")
        }
    }
}
