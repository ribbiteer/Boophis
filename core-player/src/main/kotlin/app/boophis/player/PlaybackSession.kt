// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper

/**
 * A plain Media3 [MediaSession] (no MediaSessionService / MediaBrowserService — see KICKOFF §4)
 * so steering-wheel and headset keys reach the player. Next/previous map to episodes.
 */
@OptIn(UnstableApi::class)
class PlaybackSession(context: Context, private val player: BoophisPlayer, launchIntent: PendingIntent?) {
    private val sessionPlayer = object : ForwardingPlayer(player.exoPlayer) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_PREVIOUS).add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }

        override fun seekToNext() = player.skipToNext()
        override fun seekToNextMediaItem() = player.skipToNext()
        override fun seekToPrevious() = player.skipToPrevious()
        override fun seekToPreviousMediaItem() = player.skipToPrevious()
        override fun play() = player.resume()
        override fun pause() = player.pause()
    }

    val session: MediaSession = MediaSession.Builder(context, sessionPlayer)
        .setId("boophis")
        .apply { launchIntent?.let { setSessionActivity(it) } }
        .build()

    fun release() = session.release()
}

/**
 * Foreground service (type mediaPlayback) that keeps the process alive while video plays with the
 * phone screen off or another Android Auto app in front. Holds no player; [PlaybackSession] does.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(UnstableApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = current
        if (intent?.action == ACTION_STOP || session == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val title = session.player.mediaMetadata.title ?: "Boophis"
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_boophis)
            .setContentTitle(title)
            .setContentText(session.player.mediaMetadata.artist)
            .setContentIntent(session.sessionActivity)
            .setOngoing(true)
            .setSilent(true)
            .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (t: Throwable) {
            Log.w("BoophisPlayer", "startForeground failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "app.boophis.player.STOP"

        @Volatile internal var current: MediaSession? = null

        /** Call when playback starts. Swallows background-start restrictions (the car binding keeps us alive). */
        fun start(context: Context, session: MediaSession) {
            current = session
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (t: Throwable) {
                Log.w("BoophisPlayer", "can't start playback service from background", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
            } catch (_: Throwable) {
                // Not running or not allowed; nothing to stop.
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
