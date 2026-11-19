// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import kotlin.math.sqrt

/** Pure-Kotlin frame selection for frame-grab artwork: avoids near-black or flat frames. */
object FrameQuality {
    /** Candidate positions as fractions of the duration, tried in order. */
    val POSITIONS = listOf(0.10, 0.25, 0.40, 0.60)
    const val SAMPLE_W = 32
    const val SAMPLE_H = 18
    const val MIN_MEAN = 0.16f
    const val MIN_STD = 0.06f

    data class Stats(val mean: Float, val std: Float) {
        val score: Float get() = mean + std
    }

    /** [luma] values in 0..1. */
    fun stats(luma: FloatArray): Stats {
        if (luma.isEmpty()) return Stats(0f, 0f)
        val mean = luma.sum() / luma.size
        var v = 0f
        for (l in luma) v += (l - mean) * (l - mean)
        return Stats(mean, sqrt(v / luma.size))
    }

    fun isAcceptable(s: Stats) = s.mean >= MIN_MEAN && s.std >= MIN_STD

    fun better(a: Stats, than: Stats) = a.score > than.score

    /** Index of the first acceptable candidate, else the one with the best mean + std; -1 if empty. */
    fun pick(candidates: List<Stats>): Int {
        val ok = candidates.indexOfFirst { isAcceptable(it) }
        if (ok >= 0) return ok
        return candidates.indices.maxByOrNull { candidates[it].score } ?: -1
    }
}
