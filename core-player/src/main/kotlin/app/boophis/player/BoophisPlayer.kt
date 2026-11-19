// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.boophis.model.AutoNextCountdown
import app.boophis.model.Episode
import app.boophis.model.MediaLibrary
import app.boophis.model.MediaRef
import app.boophis.model.Playable
import app.boophis.model.PlayerController
import app.boophis.model.PlayerState
import app.boophis.model.TrackInfo
import app.boophis.model.TrackType
import app.boophis.model.WatchStateStore
import app.boophis.model.rules.ResumePolicy
import app.boophis.model.rules.TrackSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * The player core: one ExoPlayer for the process, KICKOFF §6 automation on top.
 * Must be created and used on the main thread; [scope] must be a main-thread scope.
 */
@OptIn(UnstableApi::class)
class BoophisPlayer(
    context: Context,
    private val library: MediaLibrary,
    private val watch: WatchStateStore,
    private val scope: CoroutineScope,
) : PlayerController {

    private val appContext = context.applicationContext
    val exoPlayer: ExoPlayer = PlayerEngine.create(appContext)
    private val prefs = appContext.getSharedPreferences("boophis_player", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var current: Playable? = null
    private var nextEpisode: Episode? = null
    private var tracksApplied = false
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var countdownJob: Job? = null
    private var openWatchdog: Job? = null
    private var lastSaveAt = 0L

    init {
        exoPlayer.volume = prefs.getFloat(KEY_VOLUME, 1f)
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
                if (isPlaying) startTicker() else saveProgress(force = true)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                publish()
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!tracksApplied && !tracks.isEmpty) {
                    tracksApplied = true
                    applyAutoTracks(tracks)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playback error", error)
                _state.update { it.copy(error = describe(error), isPlaying = false, isBuffering = false) }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                publish()
            }

            override fun onVolumeChanged(volume: Float) {
                prefs.edit().putFloat(KEY_VOLUME, volume).apply()
            }
        })
    }

    // ---- PlayerController ----

    override fun play(ref: MediaRef, fromStart: Boolean) {
        loadJob?.cancel()
        cancelCountdown()
        // Clear any ended/error flag right away so the player screen doesn't bounce out.
        _state.update { it.copy(ended = false, error = null, autoNext = null, isBuffering = true) }
        loadJob = scope.launch {
            saveProgress(force = true)
            val item = library.playable(ref)
            if (item == null) {
                _state.value = PlayerState(error = "This video is no longer in the library.")
                return@launch
            }
            load(item, fromStart)
        }
    }

    override fun togglePlayPause() {
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0)
            exoPlayer.play()
        } else if (exoPlayer.playWhenReady) {
            exoPlayer.pause()
        } else {
            resume()
        }
    }

    override fun pause() {
        exoPlayer.pause()
    }

    override fun resume() {
        if (current == null) return
        if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
        exoPlayer.play()
    }

    override fun seekTo(positionMs: Long) {
        val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exoPlayer.seekTo(positionMs.coerceIn(0, dur))
        publish()
    }

    override fun seekBy(deltaMs: Long) = seekTo(exoPlayer.currentPosition + deltaMs)

    override fun skipToNext() {
        val next = _state.value.autoNext?.next ?: nextEpisode ?: return
        cancelCountdown()
        scope.launch {
            markCurrentWatched()
            load(next, fromStart = false)
        }
    }

    override fun skipToPrevious() {
        if (exoPlayer.currentPosition > 5_000) {
            seekTo(0)
            return
        }
        val ep = current as? Episode ?: run { seekTo(0); return }
        scope.launch {
            val eps = library.showDetail(ep.showId).first()?.episodes.orEmpty()
                .filter { it.seasonNumber > 0 }
                .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            val idx = eps.indexOfFirst { it.id == ep.id }
            val prev = if (idx > 0) eps[idx - 1] else null
            if (prev == null) seekTo(0) else {
                saveProgress(force = true)
                load(prev, fromStart = true)
            }
        }
    }

    override fun cancelAutoNext() {
        if (_state.value.autoNext == null) return
        cancelCountdown()
        _state.update { it.copy(autoNext = null, ended = true) }
    }

    override fun stop() {
        loadJob?.cancel()
        cancelCountdown()
        saveProgress(force = true)
        tickJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        current = null
        nextEpisode = null
        _state.value = PlayerState()
    }

    /** Pause and persist (car disconnect, app going away). */
    fun pauseAndSave() {
        exoPlayer.pause()
        saveProgress(force = true)
    }

    /** Has an item loaded (playing or paused). */
    val hasMedia: Boolean get() = current != null

    // ---- internals ----

    private suspend fun load(item: Playable, fromStart: Boolean) {
        current = item
        tracksApplied = false
        nextEpisode = (item as? Episode)?.let { library.nextEpisodeAfter(it.id) }
        val file = File(item.file.path)
        val subs = item.file.tracks.filter { it.type == TrackType.SUBTITLE && it.externalPath != null }.mapNotNull { t ->
            val mime = subtitleMime(t.externalPath!!) ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(t.externalPath)))
                .setMimeType(mime)
                .setLanguage(t.language)
                .setLabel(t.label)
                .setSelectionFlags(
                    (if (t.isForced) C.SELECTION_FLAG_FORCED else 0) or (if (t.isDefault) C.SELECTION_FLAG_DEFAULT else 0),
                )
                .setRoleFlags(if (t.isHearingImpaired) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else 0)
                .build()
        }
        val art = withContext(Dispatchers.IO) { artworkJpeg(item.artwork.thumb ?: item.artwork.poster) }
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId("${item.ref.kind}:${item.ref.id}")
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(displayTitle(item))
                    .setArtist((item as? Episode)?.showTitle)
                    .setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build(),
            )
            .build()
        // Re-read watch state: the library item may be stale relative to the last save.
        val fresh = withContext(NonCancellable) { library.playable(item.ref) } ?: item
        val start = if (fromStart) null else ResumePolicy.resumePosition(fresh.watch)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        exoPlayer.setMediaItem(mediaItem, start ?: 0L)
        exoPlayer.prepare()
        exoPlayer.play()
        lastSaveAt = System.currentTimeMillis()
        _state.value = PlayerState(item = item, isBuffering = true, positionMs = start ?: 0L, hasNext = nextEpisode != null)
        // A file that never yields tracks (unsupported/odd container) must not hang the player forever.
        openWatchdog?.cancel()
        openWatchdog = scope.launch {
            delay(OPEN_TIMEOUT_MS)
            if (current?.ref == item.ref && !tracksApplied && _state.value.error == null) {
                Log.w(TAG, "no tracks after ${OPEN_TIMEOUT_MS}ms: ${item.file.path}")
                exoPlayer.stop()
                _state.update { it.copy(error = "Couldn't open this video (unsupported file layout).", isBuffering = false, isPlaying = false) }
            }
        }
        Log.i(TAG, "load ${item.ref} start=$start next=${nextEpisode?.code}")
    }

    private fun applyAutoTracks(tracks: Tracks) {
        val audio = mutableListOf<Pair<Tracks.Group, Int>>()
        val text = mutableListOf<Pair<Tracks.Group, Int>>()
        for (g in tracks.groups) {
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> audio += g to i
                    C.TRACK_TYPE_TEXT -> text += g to i
                }
            }
        }
        val audioInfo = audio.map { (g, i) -> g.getTrackFormat(i).toTrackInfo(TrackType.AUDIO) }
        val textInfo = text.map { (g, i) -> g.getTrackFormat(i).toTrackInfo(TrackType.SUBTITLE) }
        val choice = TrackSelection.choose(audioInfo, textInfo)
        val b = exoPlayer.trackSelectionParameters.buildUpon()
        choice.audioIndex?.let { idx ->
            val (g, i) = audio[idx]
            b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
        }
        val sub = choice.subtitleIndex
        if (sub == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val (g, i) = text[sub]
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
        }
        exoPlayer.trackSelectionParameters = b.build()
        Log.i(TAG, "auto tracks: audio=${choice.audioIndex?.let { audioInfo[it].language }} sub=${sub?.let { textInfo[it].language + if (textInfo[it].isForced) "(forced)" else "" } ?: "off"} of ${audioInfo.size}a/${textInfo.size}s")
    }

    private fun onEnded() {
        val item = current ?: return
        scope.launch { markCurrentWatched() }
        val next = nextEpisode
        if (item is Episode && next != null) {
            startCountdown(next)
        } else {
            _state.update { it.copy(ended = true, isPlaying = false) }
        }
    }

    private fun startCountdown(next: Episode) {
        cancelCountdown()
        countdownJob = scope.launch {
            for (s in AUTO_NEXT_SECONDS downTo 1) {
                _state.update { it.copy(autoNext = AutoNextCountdown(next, s), isPlaying = false) }
                delay(1_000)
            }
            _state.update { it.copy(autoNext = null) }
            load(next, fromStart = false)
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    private suspend fun markCurrentWatched() {
        val item = current ?: return
        val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: item.file.durationMs
        withContext(NonCancellable) { watch.savePosition(item.ref, dur, dur) }
    }

    private fun saveProgress(force: Boolean) {
        val item = current ?: return
        val pos = exoPlayer.currentPosition
        val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (dur <= 0 && pos <= 0) return
        if (exoPlayer.playbackState == Player.STATE_ENDED) return // handled by markCurrentWatched
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveAt < SAVE_INTERVAL_MS) return
        lastSaveAt = now
        scope.launch { withContext(NonCancellable) { watch.savePosition(item.ref, pos, dur) } }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                publish()
                saveProgress(force = false)
                delay(500)
            }
        }
    }

    private fun publish() {
        val item = current
        val vs = exoPlayer.videoSize
        _state.update {
            it.copy(
                item = item,
                isPlaying = exoPlayer.isPlaying,
                isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0),
                durationMs = exoPlayer.duration.takeIf { d -> d != C.TIME_UNSET } ?: (item?.file?.durationMs ?: 0),
                videoAspectRatio = if (vs.width > 0 && vs.height > 0) vs.width * vs.pixelWidthHeightRatio / vs.height else null,
                hasNext = nextEpisode != null,
                ended = if (exoPlayer.playbackState == Player.STATE_ENDED) it.ended else false,
                error = if (exoPlayer.playerError == null) null else it.error,
            )
        }
    }

    private fun Format.toTrackInfo(type: TrackType) = TrackInfo(
        type = type,
        language = language,
        label = label,
        codec = sampleMimeType,
        channels = channelCount.takeIf { it != Format.NO_VALUE },
        isDefault = (selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
        isForced = (selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
        isHearingImpaired = (roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0,
    )

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file is missing. Try Rescan."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Boophis can't read this file. Allow All files access."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This phone can't decode this video format."
        else -> "Playback failed (${e.errorCodeName})."
    }

    fun release() {
        stop()
        exoPlayer.release()
    }

    companion object {
        private const val TAG = "BoophisPlayer"
        private const val KEY_VOLUME = "volume"
        const val AUTO_NEXT_SECONDS = 10
        private const val OPEN_TIMEOUT_MS = 20_000L
        private const val SAVE_INTERVAL_MS = 10_000L
        private const val ARTWORK_MAX_PX = 512

        /**
         * The session's artwork as JPEG bytes, at most [ARTWORK_MAX_PX] on the long side. Android
         * Auto's dashboard card and the lock screen run in other apps, which can't open a file://
         * path inside Boophis's private storage, so the session carries the picture itself.
         */
        internal fun artworkJpeg(path: String?): ByteArray? {
            if (path == null) return null
            return runCatching { scaledJpeg(path) }.onFailure { Log.w(TAG, "artwork $path", it) }.getOrNull()
        }

        private fun scaledJpeg(path: String): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            if (longSide <= 0) return null
            var sample = 1
            while (longSide / (sample * 2) >= ARTWORK_MAX_PX) sample *= 2
            val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = ARTWORK_MAX_PX.toFloat() / maxOf(decoded.width, decoded.height)
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
            } else {
                decoded
            }
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }

        fun displayTitle(item: Playable): String = when (item) {
            is Episode -> "${item.code} · ${item.title}"
            else -> item.title
        }

        private fun subtitleMime(path: String): String? = when (path.substringAfterLast('.').lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            else -> null
        }
    }
}
