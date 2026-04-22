package org.read.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun isActivePlaybackUtteranceForHeuristics(
    callbackUtteranceId: String?,
    activeUtteranceId: String?
): Boolean {
    return callbackUtteranceId != null && callbackUtteranceId == activeUtteranceId
}

internal fun shouldRetrySpeechStartupForHeuristics(hasRetriedSpeechStart: Boolean): Boolean {
    return !hasRetriedSpeechStart
}

class ReaderPlaybackService : MediaSessionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val speechSettingsRepository by lazy {
        SpeechSettingsRepository(applicationContext)
    }
    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private val notificationArtwork by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.playback_artwork)
    }
    private val notificationArtworkData by lazy {
        notificationArtwork?.let { bitmap ->
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        }
    }
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "org.read.mobile:tts").apply {
            setReferenceCounted(false)
        }
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        mainHandler.post { player?.pause() }
                    }
                }
            }
            .build()
    }

    private var player: ExoPlayer? = null
    private var sessionPlayer: Player? = null
    private var mediaSession: MediaSession? = null
    private var tts: TextToSpeech? = null
    private var currentUtteranceId: String? = null
    private var currentUtteranceBaseOffset: Int = 0
    private var currentUtterancePlaybackPlan: SpeechPlaybackPlan? = null
    private var canceledUtteranceId: String? = null
    private var pendingRangeUpdate: PendingRangeUpdate? = null
    private var rangeUpdatePosted = false
    private var pendingPlayAfterInit = false
    private var hasRetriedSpeechStart = false
    private var loadedDocumentId: String? = null
    private var speechStartWatchdogUtteranceId: String? = null
    private var latestPlaybackState: PlaybackUiState = ReaderPlaybackStore.uiState.value
    private val delayedNotReadyStatusRunnable = Runnable {
        val state = ReaderPlaybackStore.uiState.value
        if (!state.isReady && !state.isSpeaking && pendingPlayAfterInit) {
            ReaderPlaybackStore.setStatus("Read aloud is not ready on this device.")
        }
    }
    private val wakeLockRefreshRunnable = object : Runnable {
        override fun run() {
            if (player?.isPlaying != true) {
                return
            }
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            scheduleWakeLockRefresh()
        }
    }
    private val applyPendingRangeUpdateRunnable = Runnable {
        rangeUpdatePosted = false
        val update = pendingRangeUpdate
        pendingRangeUpdate = null
        if (update != null) {
            ReaderPlaybackStore.markRange(update.start, update.end)
        }
    }
    private val speechStartWatchdogRunnable = Runnable {
        val expectedUtteranceId = speechStartWatchdogUtteranceId ?: return@Runnable
        if (!isActivePlaybackUtteranceForHeuristics(expectedUtteranceId, currentUtteranceId)) {
            return@Runnable
        }

        if (player?.isPlaying != true || ReaderPlaybackStore.uiState.value.isSpeaking) {
            return@Runnable
        }

        recoverFromSpeechStartupFailure("Read aloud could not start.")
    }

    override fun onCreate() {
        super.onCreate()
        ReaderPlaybackStore.setSpeed(speechSettingsRepository.selectedSpeed())
        latestPlaybackState = ReaderPlaybackStore.uiState.value

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                false
            )
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        ensureForegroundNotification()
                        if (requestAudioFocus() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            pause()
                            ReaderPlaybackStore.setStatus("Audio focus was not granted.")
                            return
                        }
                        acquireWakeLock()
                        if (currentUtteranceId == null) {
                            playCurrentSegment()
                        }
                    } else {
                        cancelSpeechStartWatchdog()
                        currentUtteranceId?.let { canceledUtteranceId = it }
                        tts?.stop()
                        clearPendingRangeUpdate()
                        currentUtteranceId = null
                        currentUtterancePlaybackPlan = null
                        ReaderPlaybackStore.setSpeaking(false)
                        updateNotification()
                        stopForeground(Service.STOP_FOREGROUND_DETACH)
                        releaseWakeLock()
                        abandonAudioFocus()
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    updateSpeed(playbackParameters.speed)
                }
            })
        }

        sessionPlayer = object : ForwardingPlayer(player!!) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .remove(Player.COMMAND_SEEK_TO_NEXT)
                    .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .build()
            }

            override fun getDuration(): Long {
                return if (ReaderPlaybackStore.hasSegments()) {
                    LOGICAL_TIMELINE_DURATION_MS
                } else {
                    C.TIME_UNSET
                }
            }

            override fun getCurrentPosition(): Long {
                return if (ReaderPlaybackStore.hasSegments()) {
                    (ReaderPlaybackStore.progressFraction() * LOGICAL_TIMELINE_DURATION_MS.toDouble())
                        .toLong()
                        .coerceIn(0L, LOGICAL_TIMELINE_DURATION_MS)
                } else {
                    0L
                }
            }

            override fun getBufferedPosition(): Long = duration.coerceAtLeast(0L)

            override fun isCurrentMediaItemSeekable(): Boolean = ReaderPlaybackStore.hasSegments()

            override fun getSeekBackIncrement(): Long = 1_000L

            override fun getSeekForwardIncrement(): Long = 1_000L

            override fun seekBack() {
                updateSpeed(ReaderPlaybackStore.uiState.value.speed - 0.1f)
            }

            override fun seekForward() {
                updateSpeed(ReaderPlaybackStore.uiState.value.speed + 0.1f)
            }

            override fun seekTo(positionMs: Long) {
                seekStoreTo(positionMs)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                seekStoreTo(positionMs)
            }

            private fun seekStoreTo(positionMs: Long) {
                if (!ReaderPlaybackStore.hasSegments()) {
                    return
                }

                val fraction = if (LOGICAL_TIMELINE_DURATION_MS <= 0L) {
                    0f
                } else {
                    (positionMs.toDouble() / LOGICAL_TIMELINE_DURATION_MS.toDouble()).toFloat()
                }.coerceIn(0f, 1f)

                ReaderPlaybackStore.seekToProgressFraction(fraction)
                updateNotification()

                if (player?.isPlaying == true) {
                    currentUtteranceId?.let { canceledUtteranceId = it }
                    tts?.stop()
                    playCurrentSegment()
                }
            }
        }

        mediaSession = MediaSession.Builder(this, sessionPlayer!!)
            .setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this,
                    100,
                    buildOpenMainActivityIntent(),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setCallback(
                object : MediaSession.Callback {
                    override fun onConnect(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo
                    ): MediaSession.ConnectionResult =
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()

                    override fun onMediaButtonEvent(
                        session: MediaSession,
                        controllerInfo: MediaSession.ControllerInfo,
                        intent: Intent
                    ): Boolean {
                        @Suppress("DEPRECATION")
                        val keyEvent = intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                            ?: return false

                        if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                            return true
                        }

                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                mainHandler.post { skipNext() }
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                mainHandler.post { skipPrevious() }
                                return true
                            }
                        }

                        return false
                    }
                }
            )
            .build()

        createNotificationChannel()
        setMediaNotificationProvider(
            object : MediaNotification.Provider {
                override fun createNotification(
                    mediaSession: MediaSession,
                    mediaButtonPreferences: com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton>,
                    actionFactory: MediaNotification.ActionFactory,
                    onNotificationChangedCallback: MediaNotification.Provider.Callback
                ): MediaNotification {
                    return MediaNotification(NOTIFICATION_ID, buildMediaNotification(mediaSession))
                }

                override fun handleCustomCommand(
                    mediaSession: MediaSession,
                    action: String,
                    extras: android.os.Bundle
                ): Boolean = false
            }
        )

        serviceScope.launch {
            ReaderPlaybackStore.uiState.collectLatest { state ->
                latestPlaybackState = state
            }
        }

        initializeTts()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_PLAY -> handlePlayRequest()
            ACTION_PAUSE -> player?.pause()
            ACTION_SKIP_NEXT -> skipNext()
            ACTION_SKIP_PREVIOUS -> skipPrevious()
            ACTION_SPEED_DOWN -> updateSpeed(ReaderPlaybackStore.uiState.value.speed - 0.1f)
            ACTION_SPEED_UP -> updateSpeed(ReaderPlaybackStore.uiState.value.speed + 0.1f)
            ACTION_SET_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, ReaderPlaybackStore.uiState.value.speed)
                player?.setPlaybackParameters(PlaybackParameters(speed))
            }
            ACTION_SET_VOICE -> {
                val voiceName = intent.getStringExtra(EXTRA_VOICE_NAME)?.takeIf { it.isNotBlank() }
                speechSettingsRepository.saveSelectedVoiceName(voiceName)
                applyPreferredVoice(tts, preferredVoiceName = voiceName)
            }
            ACTION_SEEK_TO_FRACTION -> {
                val fraction = intent.getFloatExtra(EXTRA_PROGRESS_FRACTION, ReaderPlaybackStore.progressFraction())
                ReaderPlaybackStore.seekToProgressFraction(fraction)
                updateNotification()
                if (player?.isPlaying == true) {
                    currentUtteranceId?.let { canceledUtteranceId = it }
                    tts?.stop()
                    playCurrentSegment()
                }
            }
            ACTION_SEEK_TO_INDEX -> {
                val index = intent.getIntExtra(EXTRA_SEGMENT_INDEX, 0)
                ReaderPlaybackStore.setCurrentSegmentIndex(index)
                updateNotification()
                if (player?.isPlaying == true) {
                    playCurrentSegment()
                }
            }
        }

        return result
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(wakeLockRefreshRunnable)
        cancelDelayedNotReadyStatus()
        cancelSpeechStartWatchdog()
        serviceScope.cancel()
        tts?.stop()
        clearPendingRangeUpdate()
        tts?.shutdown()
        tts = null
        player?.release()
        player = null
        sessionPlayer = null
        mediaSession?.release()
        mediaSession = null
        releaseWakeLock()
        abandonAudioFocus()
        ReaderPlaybackStore.setSpeaking(false)
        ReaderPlaybackStore.markReady(false)
    }

    private fun initializeTts() {
        tts = TextToSpeech(applicationContext) { status ->
            mainHandler.post {
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    cancelDelayedNotReadyStatus()
                    ReaderPlaybackStore.markReady(false)
                    ReaderPlaybackStore.setStatus("Text-to-speech initialization failed.")
                    return@post
                }

                val languageStatus = engine.setLanguage(java.util.Locale.getDefault())
                if (
                    languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                    languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    cancelDelayedNotReadyStatus()
                    ReaderPlaybackStore.markReady(false)
                    ReaderPlaybackStore.setStatus("This device needs a text-to-speech voice installed.")
                    return@post
                }

                engine.setSpeechRate(ReaderPlaybackStore.uiState.value.speed)
                applyPreferredVoice(engine)
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post {
                            if (!isActivePlaybackUtteranceForHeuristics(utteranceId, currentUtteranceId)) {
                                return@post
                            }
                            cancelSpeechStartWatchdog()
                            hasRetriedSpeechStart = false
                            ReaderPlaybackStore.setSpeaking(true)
                        }
                    }

                    override fun onRangeStart(
                        utteranceId: String?,
                        start: Int,
                        end: Int,
                        frame: Int
                    ) {
                        if (!isActivePlaybackUtteranceForHeuristics(utteranceId, currentUtteranceId)) {
                            return
                        }
                        val playbackPlan = currentUtterancePlaybackPlan
                        val mappedStart = playbackPlan?.mapSpokenOffsetToOriginal(start, preferEnd = false) ?: start
                        val mappedEnd = playbackPlan?.mapSpokenOffsetToOriginal(end, preferEnd = true) ?: end
                        queueRangeUpdate(
                            start = currentUtteranceBaseOffset + mappedStart,
                            end = currentUtteranceBaseOffset + mappedEnd
                        )
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            if (utteranceId != null && utteranceId == canceledUtteranceId) {
                                canceledUtteranceId = null
                                clearPendingRangeUpdate()
                                cancelSpeechStartWatchdog()
                                return@post
                            }

                            if (!isActivePlaybackUtteranceForHeuristics(utteranceId, currentUtteranceId)) {
                                return@post
                            }

                            clearPendingRangeUpdate()
                            cancelSpeechStartWatchdog()
                            currentUtteranceId = null
                            currentUtteranceBaseOffset = 0
                            currentUtterancePlaybackPlan = null
                            if (player?.isPlaying != true) {
                                ReaderPlaybackStore.setSpeaking(false)
                                return@post
                            }

                            if (ReaderPlaybackStore.advanceToNextSegment()) {
                                updateNotification()
                                playCurrentSegment()
                            } else {
                                ReaderPlaybackStore.setStatus("Finished reading.")
                                player?.pause()
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            if (utteranceId != null && utteranceId == canceledUtteranceId) {
                                canceledUtteranceId = null
                                clearPendingRangeUpdate()
                                cancelSpeechStartWatchdog()
                                return@post
                            }

                            if (!isActivePlaybackUtteranceForHeuristics(utteranceId, currentUtteranceId)) {
                                return@post
                            }

                            recoverFromSpeechStartupFailure("Read aloud failed during playback.")
                        }
                    }
                })

                ReaderPlaybackStore.markReady(true)
                cancelDelayedNotReadyStatus()
                if (pendingPlayAfterInit) {
                    pendingPlayAfterInit = false
                    handlePlayRequest(resetSpeechStartupRetry = false)
                }
            }
        }
    }

    private fun handlePlayRequest(resetSpeechStartupRetry: Boolean = true) {
        if (resetSpeechStartupRetry) {
            hasRetriedSpeechStart = false
        }
        ensurePreparedPlayer()
        if (!ReaderPlaybackStore.uiState.value.isReady) {
            pendingPlayAfterInit = true
            scheduleDelayedNotReadyStatus()
            return
        }

        cancelDelayedNotReadyStatus()
        if (!ReaderPlaybackStore.hasSegments()) {
            ReaderPlaybackStore.setStatus(getString(R.string.message_nothing_readable_to_play))
            return
        }

        ensureForegroundNotification()
        player?.play()
    }

    private fun ensurePreparedPlayer() {
        val exoPlayer = player ?: return
        val state = ReaderPlaybackStore.uiState.value
        val documentId = state.currentDocumentId ?: return
        if (loadedDocumentId == documentId) {
            return
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(documentId)
            .setMediaMetadata(
                MediaMetadata.Builder().apply {
                    setTitle(state.currentTitle)
                    setDisplayTitle(state.currentTitle)
                    setIsPlayable(true)
                    notificationArtworkData?.let {
                        setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                }.build()
            )
            .build()

        val silenceSource = SilenceMediaSource(SILENCE_DURATION_US).apply {
            updateMediaItem(mediaItem)
        }

        exoPlayer.setMediaSource(silenceSource)
        exoPlayer.prepare()
        loadedDocumentId = documentId
    }

    private fun playCurrentSegment() {
        val exoPlayer = player
        val engine = tts
        if (exoPlayer == null || engine == null || !ReaderPlaybackStore.uiState.value.isReady) {
            if (pendingPlayAfterInit) {
                scheduleDelayedNotReadyStatus()
            } else {
                ReaderPlaybackStore.setStatus("Read aloud is not ready on this device.")
            }
            return
        }

        cancelDelayedNotReadyStatus()
        val segment = ReaderPlaybackStore.currentSegment()
        if (segment == null) {
            ReaderPlaybackStore.setStatus(getString(R.string.message_nothing_readable_to_play))
            exoPlayer.pause()
            return
        }

        val playbackText = ReaderPlaybackStore.currentPlaybackText()
        if (playbackText == null) {
            if (ReaderPlaybackStore.advanceToNextSegment()) {
                playCurrentSegment()
            } else {
                ReaderPlaybackStore.setStatus("Finished reading.")
                exoPlayer.pause()
            }
            return
        }

        val utteranceId = "pdf_segment_${ReaderPlaybackStore.uiState.value.currentSegmentIndex}"
        val playbackPlan = buildSpeechPlaybackPlan(playbackText)
        clearPendingRangeUpdate()
        currentUtteranceId = utteranceId
        currentUtteranceBaseOffset = ReaderPlaybackStore.uiState.value.currentCharOffset
        currentUtterancePlaybackPlan = playbackPlan
        canceledUtteranceId = null
        scheduleSpeechStartWatchdog(utteranceId)
        val result = engine.speak(playbackPlan.spokenText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            recoverFromSpeechStartupFailure("Read aloud could not start.")
        }
    }

    private fun scheduleDelayedNotReadyStatus() {
        mainHandler.removeCallbacks(delayedNotReadyStatusRunnable)
        mainHandler.postDelayed(delayedNotReadyStatusRunnable, NOT_READY_STATUS_GRACE_MS)
    }

    private fun cancelDelayedNotReadyStatus() {
        mainHandler.removeCallbacks(delayedNotReadyStatusRunnable)
    }

    private fun scheduleSpeechStartWatchdog(utteranceId: String) {
        speechStartWatchdogUtteranceId = utteranceId
        mainHandler.removeCallbacks(speechStartWatchdogRunnable)
        mainHandler.postDelayed(speechStartWatchdogRunnable, SPEECH_START_TIMEOUT_MS)
    }

    private fun cancelSpeechStartWatchdog() {
        speechStartWatchdogUtteranceId = null
        mainHandler.removeCallbacks(speechStartWatchdogRunnable)
    }

    private fun resetTtsEngine() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun recoverFromSpeechStartupFailure(statusMessage: String) {
        cancelDelayedNotReadyStatus()
        cancelSpeechStartWatchdog()
        clearPendingRangeUpdate()
        currentUtteranceId = null
        currentUtteranceBaseOffset = 0
        currentUtterancePlaybackPlan = null
        canceledUtteranceId = null
        ReaderPlaybackStore.setSpeaking(false)

        if (!shouldRetrySpeechStartupForHeuristics(hasRetriedSpeechStart)) {
            ReaderPlaybackStore.setStatus(statusMessage)
            player?.pause()
            return
        }

        hasRetriedSpeechStart = true
        pendingPlayAfterInit = true
        ReaderPlaybackStore.markReady(false)
        ReaderPlaybackStore.setStatus("Retrying read aloud...")
        player?.pause()
        resetTtsEngine()
        initializeTts()
    }

    private fun skipNext() {
        if (!ReaderPlaybackStore.hasSegments()) {
            ReaderPlaybackStore.setStatus("Nothing loaded to skip.")
            return
        }

        ReaderPlaybackStore.setCurrentSegmentIndex(
            (ReaderPlaybackStore.uiState.value.currentSegmentIndex + 1)
                .coerceAtMost(ReaderPlaybackStore.segmentCount() - 1)
        )
        updateNotification()

        if (player?.isPlaying == true) {
            currentUtteranceId?.let { canceledUtteranceId = it }
            tts?.stop()
            playCurrentSegment()
        }
    }

    private fun skipPrevious() {
        if (!ReaderPlaybackStore.hasSegments()) {
            ReaderPlaybackStore.setStatus("Nothing loaded to skip.")
            return
        }

        ReaderPlaybackStore.setCurrentSegmentIndex(
            (ReaderPlaybackStore.uiState.value.currentSegmentIndex - 1).coerceAtLeast(0)
        )
        updateNotification()

        if (player?.isPlaying == true) {
            currentUtteranceId?.let { canceledUtteranceId = it }
            tts?.stop()
            playCurrentSegment()
        }
    }

    private fun updateSpeed(speed: Float) {
        val clamped = normalizeReaderSpeed(speed)
        ReaderPlaybackStore.setSpeed(clamped)
        speechSettingsRepository.saveSelectedSpeed(clamped)
        tts?.setSpeechRate(clamped)
        updateNotification()
    }

    private fun queueRangeUpdate(start: Int, end: Int) {
        pendingRangeUpdate = PendingRangeUpdate(start = start, end = end)
        if (!rangeUpdatePosted) {
            rangeUpdatePosted = true
            mainHandler.post(applyPendingRangeUpdateRunnable)
        }
    }

    private fun clearPendingRangeUpdate() {
        pendingRangeUpdate = null
        rangeUpdatePosted = false
        mainHandler.removeCallbacks(applyPendingRangeUpdateRunnable)
    }

    private fun applyPreferredVoice(
        engine: TextToSpeech?,
        preferredVoiceName: String? = speechSettingsRepository.selectedVoiceName()
    ) {
        val ttsEngine = engine ?: return
        val targetName = preferredVoiceName?.takeIf { it.isNotBlank() }
        if (targetName == null) {
            ttsEngine.setLanguage(java.util.Locale.getDefault())
            return
        }

        val voice = ttsEngine.voices
            ?.firstOrNull { it.name == targetName && !it.isNetworkConnectionRequired }
        if (voice == null) {
            ReaderPlaybackStore.setStatus(getString(R.string.message_voice_not_available))
            return
        }

        val result = ttsEngine.setVoice(voice)
        if (result == TextToSpeech.ERROR) {
            ReaderPlaybackStore.setStatus(getString(R.string.message_voice_not_available))
        }
    }

    private fun requestAudioFocus(): Int = audioManager.requestAudioFocus(audioFocusRequest)

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        scheduleWakeLockRefresh()
    }

    private fun releaseWakeLock() {
        mainHandler.removeCallbacks(wakeLockRefreshRunnable)
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun scheduleWakeLockRefresh() {
        mainHandler.removeCallbacks(wakeLockRefreshRunnable)
        mainHandler.postDelayed(wakeLockRefreshRunnable, WAKE_LOCK_REFRESH_MS)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_playback),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_playback_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildMediaNotification(session: MediaSession): Notification {
        val uiState = ReaderPlaybackStore.uiState.value
        val isPlaying = session.player.isPlaying || session.player.playWhenReady
        val contentIntent = PendingIntent.getActivity(
            this,
            100,
            buildOpenMainActivityIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val slowerIntent = PendingIntent.getService(
            this,
            104,
            Intent(this, ReaderPlaybackService::class.java).setAction(ACTION_SPEED_DOWN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPauseIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, ReaderPlaybackService::class.java)
                .setAction(if (isPlaying) ACTION_PAUSE else ACTION_PLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val fasterIntent = PendingIntent.getService(
            this,
            105,
            Intent(this, ReaderPlaybackService::class.java).setAction(ACTION_SPEED_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val slowerAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_revert),
            getString(R.string.action_slower),
            slowerIntent
        ).build()
        val playPauseAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            ),
            getString(if (isPlaying) R.string.action_pause else R.string.action_play),
            playPauseIntent
        ).build()
        val fasterAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_input_add),
            getString(R.string.action_faster),
            fasterIntent
        ).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setLargeIcon(notificationArtwork)
            .setContentTitle(uiState.currentTitle.ifBlank { getString(R.string.app_name) })
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(slowerAction)
            .addAction(playPauseAction)
            .addAction(fasterAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.platformToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun ensureForegroundNotification() {
        val session = mediaSession ?: return
        startForeground(NOTIFICATION_ID, buildMediaNotification(session))
    }

    private fun updateNotification() {
        val session = mediaSession ?: return
        notificationManager.notify(NOTIFICATION_ID, buildMediaNotification(session))
    }

    private fun buildOpenMainActivityIntent(): Intent {
        return Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
    }

    private data class PendingRangeUpdate(
        val start: Int,
        val end: Int
    )

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "turing_pdf_playback"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_PLAY = "org.read.mobile.action.PLAY"
        private const val ACTION_PAUSE = "org.read.mobile.action.PAUSE"
        private const val ACTION_SKIP_NEXT = "org.read.mobile.action.SKIP_NEXT"
        private const val ACTION_SKIP_PREVIOUS = "org.read.mobile.action.SKIP_PREVIOUS"
        private const val ACTION_SPEED_DOWN = "org.read.mobile.action.SPEED_DOWN"
        private const val ACTION_SPEED_UP = "org.read.mobile.action.SPEED_UP"
        private const val ACTION_SET_SPEED = "org.read.mobile.action.SET_SPEED"
        private const val ACTION_SET_VOICE = "org.read.mobile.action.SET_VOICE"
        private const val ACTION_SEEK_TO_FRACTION = "org.read.mobile.action.SEEK_TO_FRACTION"
        private const val ACTION_SEEK_TO_INDEX = "org.read.mobile.action.SEEK_TO_INDEX"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_VOICE_NAME = "voice_name"
        private const val EXTRA_PROGRESS_FRACTION = "progress_fraction"
        private const val EXTRA_SEGMENT_INDEX = "segment_index"
        private const val SILENCE_DURATION_US = 86_400_000_000L
        private const val LOGICAL_TIMELINE_DURATION_MS = 1_000_000L
        private const val NOT_READY_STATUS_GRACE_MS = 1_500L
        private const val SPEECH_START_TIMEOUT_MS = 2_500L
        private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L
        private const val WAKE_LOCK_REFRESH_MS = 10 * 60 * 1000L

        fun play(context: Context) {
            startForeground(context, Intent(context, ReaderPlaybackService::class.java).setAction(ACTION_PLAY))
        }

        fun pause(context: Context) {
            start(context, Intent(context, ReaderPlaybackService::class.java).setAction(ACTION_PAUSE))
        }

        fun skipNext(context: Context) {
            start(context, Intent(context, ReaderPlaybackService::class.java).setAction(ACTION_SKIP_NEXT))
        }

        fun skipPrevious(context: Context) {
            start(context, Intent(context, ReaderPlaybackService::class.java).setAction(ACTION_SKIP_PREVIOUS))
        }

        fun setSpeed(context: Context, speed: Float) {
            start(
                context,
                Intent(context, ReaderPlaybackService::class.java)
                    .setAction(ACTION_SET_SPEED)
                    .putExtra(EXTRA_SPEED, speed)
            )
        }

        fun setVoice(context: Context, voiceName: String?) {
            start(
                context,
                Intent(context, ReaderPlaybackService::class.java)
                    .setAction(ACTION_SET_VOICE)
                    .putExtra(EXTRA_VOICE_NAME, voiceName)
            )
        }

        fun seekToFraction(context: Context, fraction: Float) {
            start(
                context,
                Intent(context, ReaderPlaybackService::class.java)
                    .setAction(ACTION_SEEK_TO_FRACTION)
                    .putExtra(EXTRA_PROGRESS_FRACTION, fraction.coerceIn(0f, 1f))
            )
        }

        fun seekToSegment(context: Context, index: Int) {
            start(
                context,
                Intent(context, ReaderPlaybackService::class.java)
                    .setAction(ACTION_SEEK_TO_INDEX)
                    .putExtra(EXTRA_SEGMENT_INDEX, index)
            )
        }

        private fun start(context: Context, intent: Intent) {
            context.startService(intent)
        }

        private fun startForeground(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
