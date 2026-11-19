// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.car

import android.content.Context
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Implemented by the Application: what the car side needs from the rest of the app.
 * Keeps car/ independent of app wiring.
 */
interface CarAppHost {
    /** The process-wide Media3 player (video picture goes into a VideoSurfaceView). */
    val mediaPlayer: Player

    /** True while something is loaded and playing (drives the Play/Pause action). */
    val isPlaying: StateFlow<Boolean>

    fun togglePlayPause()

    /** Android Auto session started (rescan + "open to Resume" logic live in the app). */
    fun onCarSessionStarted(env: CarEnv)

    fun onCarSessionEnded()

    /** The car's day/night state changed (headlights / light sensor). Night is the default. */
    fun onCarNightMode(night: Boolean) {}

    /**
     * The car's own Back key / back affordance (legacy projection route). Returns true when Boophis
     * handled it (navigated back), false when it is already at Home and Android Auto may close it.
     */
    fun onCarBack(env: CarEnv): Boolean = false

    /** Root content drawn onto the car surface, in car layout mode. */
    @Composable
    fun CarContent(env: CarEnv)

    companion object {
        fun from(context: Context): CarAppHost = context.applicationContext as CarAppHost
    }
}

/**
 * Per-car-session environment shared by the renderer and Compose. Survives surface
 * destroy/recreate (it belongs to the Session, not the Presentation).
 */
@Stable
class CarEnv {
    /** Surface size in px and density, updated per surface. */
    var surfaceWidth by mutableStateOf(0)
    var surfaceHeight by mutableStateOf(0)
    var surfaceDpi by mutableStateOf(160)

    /** Area not covered by car chrome / ActionStrip, in surface px. Null until the host reports it. */
    var visibleArea by mutableStateOf<Rect?>(null)

    /** Area guaranteed to stay uncovered, in surface px. */
    var stableArea by mutableStateOf<Rect?>(null)

    /** Number of times a surface has been attached in this session (diagnostics). */
    var surfaceGeneration by mutableStateOf(0)

    private val _homeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Emits when the car's Home action is pressed. */
    val homeRequests: SharedFlow<Unit> = _homeRequests

    internal fun requestHome() {
        _homeRequests.tryEmit(Unit)
    }

    /** Arbitrary per-session state the UI wants to keep across surface recreation. */
    val retained = HashMap<String, Any>()
}
