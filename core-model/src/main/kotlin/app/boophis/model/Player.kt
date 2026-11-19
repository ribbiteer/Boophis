// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.model

import kotlinx.coroutines.flow.StateFlow

/** Countdown card shown when an episode ends and another follows. */
data class AutoNextCountdown(
    val next: Episode,
    val secondsLeft: Int,
)

data class PlayerState(
    val item: Playable? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Width / height of the decoded video, null until known. */
    val videoAspectRatio: Float? = null,
    /** True when there is a next episode (enables the Next Episode button). */
    val hasNext: Boolean = false,
    val autoNext: AutoNextCountdown? = null,
    /** Playback reached the end with nothing to auto-play. */
    val ended: Boolean = false,
    /** Human-readable error for the player screen, null when fine. */
    val error: String? = null,
) {
    val isActive: Boolean get() = item != null
}

/**
 * UI-facing control surface of the player core (core-player implements it on Media3).
 * The video picture itself is drawn by a platform view that core-player provides separately,
 * so this interface stays free of Android types.
 */
interface PlayerController {
    /** Updated at least every 500 ms while playing. */
    val state: StateFlow<PlayerState>

    /**
     * Loads and plays [ref]. Resumes from the saved position per
     * [app.boophis.model.rules.ResumePolicy] unless [fromStart] is true.
     * Audio/subtitle tracks are chosen by [app.boophis.model.rules.TrackSelection].
     */
    fun play(ref: MediaRef, fromStart: Boolean = false)

    fun togglePlayPause()

    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    /** Jumps to the next episode immediately (also used by the countdown card's "Play now"). */
    fun skipToNext()

    /** Goes to the previous episode, or restarts the current item if > 5 s in. */
    fun skipToPrevious()

    /** Cancels the auto-next countdown (tap on the card). */
    fun cancelAutoNext()

    /** Saves progress and releases the current item. The player stays usable. */
    fun stop()
}
