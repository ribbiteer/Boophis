// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.model.rules

import app.boophis.model.WatchState

/** KICKOFF §6: resume only when > 2 min in and < 92 % done; mark watched at ≥ 92 %. */
object ResumePolicy {
    const val MIN_RESUME_MS: Long = 2 * 60 * 1000L
    const val WATCHED_FRACTION: Double = 0.92

    fun isWatched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION

    /** The position to resume from, or null to start from the beginning. */
    fun resumePosition(watch: WatchState): Long? = resumePosition(watch.positionMs, watch.durationMs, watch.watched)

    fun resumePosition(positionMs: Long, durationMs: Long, watched: Boolean = false): Long? {
        if (watched) return null
        if (positionMs <= MIN_RESUME_MS) return null
        if (durationMs > 0 && isWatched(positionMs, durationMs)) return null
        return positionMs
    }

    fun hasResumePoint(watch: WatchState): Boolean = resumePosition(watch) != null

    /**
     * New persisted state after a progress save. Reaching ≥ 92 % marks watched and clears the
     * resume point; otherwise the position is kept (even under 2 min, so the next-up logic
     * knows the item was started, but [resumePosition] ignores it).
     */
    fun afterProgress(prev: WatchState, positionMs: Long, durationMs: Long, now: Long): WatchState {
        val dur = if (durationMs > 0) durationMs else prev.durationMs
        return if (isWatched(positionMs, dur)) {
            WatchState(positionMs = 0, durationMs = dur, watched = true, lastPlayedAt = now)
        } else {
            WatchState(positionMs = positionMs.coerceAtLeast(0), durationMs = dur, watched = prev.watched, lastPlayedAt = now)
        }
    }

    /** "In progress" for the Continue Watching row. */
    fun isInProgress(watch: WatchState): Boolean = hasResumePoint(watch)
}
