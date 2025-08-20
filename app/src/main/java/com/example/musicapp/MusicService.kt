package com.example.musicapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

class MusicService : Service(), MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener {

    private var mediaPlayer: MediaPlayer? = null
    private var playlist: List<Song> = emptyList()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var timeUpdateJob: Job? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var isPlayerPrepared = false

    companion object {
        private val _currentSong = MutableStateFlow<Song?>(null)
        val currentSong = _currentSong.asStateFlow()
        private val _isPlaying = MutableStateFlow(false)
        val isPlaying = _isPlaying.asStateFlow()
        private val _currentTime = MutableStateFlow(0)
        val currentTime = _currentTime.asStateFlow()
        private val _totalDuration = MutableStateFlow(0)
        val totalDuration = _totalDuration.asStateFlow()
        private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
        val playMode = _playMode.asStateFlow()

        const val ACTION_PLAY = "PLAY"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_NEXT = "NEXT"
        const val ACTION_PREVIOUS = "PREVIOUS"
        const val ACTION_SEEK = "SEEK"
        const val ACTION_TOGGLE_PLAY_MODE = "TOGGLE_PLAY_MODE"
        const val ACTION_SET_PLAYLIST = "SET_PLAYLIST"
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (isPlayerPrepared) togglePlayPause()
        }
        override fun onPause() {
            if (isPlayerPrepared) togglePlayPause()
        }
        override fun onSkipToNext() {
            playNextSong()
        }
        override fun onSkipToPrevious() {
            playPreviousSong()
        }
        override fun onSeekTo(pos: Long) {
            if (isPlayerPrepared) seekTo(pos)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "MusicAppSession").apply {
            // 【修正】明确设置媒体按钮接收者，这是让锁屏控件工作的关键
            val mediaButtonReceiverPendingIntent =
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this@MusicService,
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            setMediaButtonReceiver(mediaButtonReceiverPendingIntent)
            setCallback(mediaSessionCallback)
            isActive = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_SET_PLAYLIST -> playlist = intent.getParcelableArrayListExtra("PLAYLIST") ?: emptyList()
            ACTION_PLAY -> {
                val songId = intent.getIntExtra("SONG_ID", -1)
                playlist.find { it.id == songId }?.let { playSong(it) }
            }
            ACTION_PAUSE -> if (playlist.isNotEmpty()) togglePlayPause()
            ACTION_NEXT -> if (playlist.isNotEmpty()) playNextSong()
            ACTION_PREVIOUS -> if (playlist.isNotEmpty()) playPreviousSong()
            ACTION_TOGGLE_PLAY_MODE -> if (playlist.isNotEmpty()) togglePlayMode()
            ACTION_SEEK -> {
                if (playlist.isNotEmpty()) {
                    val time = intent.getLongExtra("SEEK_TO", 0)
                    seekTo(time)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun playSong(song: Song) {
        isPlayerPrepared = false
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        _currentSong.value = song
        _isPlaying.value = false
        _totalDuration.value = song.duration
        _currentTime.value = 0

        // 【修正】在加载音频前，立即以“缓冲”状态启动前台服务和通知
        // 这样可以确保通知样式从一开始就是正确的，即使封面还没加载出来
        updateMediaMetadata(song, null) // 先更新文字信息
        updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
        startForeground(1, createNotification())

        song.streamUrl?.let { streamUrl ->
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                try {
                    val fullStreamUrl = if (streamUrl.startsWith("http")) streamUrl else BASE_URL + streamUrl
                    setDataSource(applicationContext, Uri.parse(fullStreamUrl))
                    setOnPreparedListener(this@MusicService)
                    setOnErrorListener(this@MusicService)
                    setOnCompletionListener { playNextSong() }
                    isLooping = _playMode.value == PlayMode.REPEAT_ONE
                    prepareAsync()
                } catch (e: IOException) {
                    Log.e("MusicService", "MediaPlayer setDataSource failed", e)
                    updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
                }
            }
        } ?: run {
            Log.e("MusicService", "Song streamUrl is null, cannot play.")
            updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
        }
    }

    override fun onPrepared(mp: MediaPlayer?) {
        isPlayerPrepared = true
        _totalDuration.value = mp?.duration?.div(1000) ?: 0
        mp?.start()
        _isPlaying.value = true
        startTimeUpdates()
        updatePlaybackState()
        // 准备好后再次更新通知，确保播放/暂停按钮状态正确
        updateNotification()
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        isPlayerPrepared = false
        _isPlaying.value = false
        mediaPlayer?.reset()
        updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
        return true
    }

    private fun togglePlayPause() {
        if (!isPlayerPrepared) return

        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
                stopForeground(false)
            } else {
                it.start()
                _isPlaying.value = true
                startForeground(1, createNotification())
            }
            updatePlaybackState()
            updateNotification()
        }
    }

    private fun playNextSong() {
        _currentSong.value?.let { current ->
            val nextSong = when (_playMode.value) {
                PlayMode.SEQUENTIAL -> playlist.getOrNull((playlist.indexOf(current) + 1) % playlist.size)
                PlayMode.SHUFFLE -> playlist.filter { it.id != current.id }.randomOrNull()
                PlayMode.REPEAT_ONE -> current
            }
            nextSong?.let { playSong(it) }
        }
    }

    private fun playPreviousSong() {
        _currentSong.value?.let { current ->
            val currentIndex = playlist.indexOf(current)
            val previousIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
            playlist.getOrNull(previousIndex)?.let { playSong(it) }
        }
    }

    private fun togglePlayMode() {
        _playMode.value = when (_playMode.value) {
            PlayMode.SEQUENTIAL -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SEQUENTIAL
        }
        mediaPlayer?.isLooping = _playMode.value == PlayMode.REPEAT_ONE
    }

    private fun seekTo(timeInMillis: Long) {
        if (!isPlayerPrepared) return

        mediaPlayer?.let {
            val safeSeekPosition = timeInMillis.coerceAtMost((_totalDuration.value * 1000).toLong())
            it.seekTo(safeSeekPosition.toInt())
            _currentTime.value = (safeSeekPosition / 1000).toInt()
            updatePlaybackState()
        }
    }

    private fun startTimeUpdates() {
        timeUpdateJob?.cancel()
        timeUpdateJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (_isPlaying.value && isPlayerPrepared) {
                    try {
                        _currentTime.value = mediaPlayer?.currentPosition?.div(1000) ?: _currentTime.value
                    } catch (e: IllegalStateException) { /* Ignored */ }
                }
                delay(200)
            }
        }
    }

    private fun updatePlaybackState(state: Int? = null, position: Long? = null) {
        val currentState = state ?: if (_isPlaying.value) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val currentPosition = position ?: try {
            if (isPlayerPrepared) mediaPlayer?.currentPosition?.toLong() ?: 0L else 0L
        } catch (e: IllegalStateException) {
            0L
        }

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(currentState, currentPosition, 1.0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO
                )
                .build()
        )
    }

    // 【修正】updateMediaMetadata 现在可以分开处理图片
    private fun updateMediaMetadata(song: Song, bitmap: Bitmap? = null) {
        if (bitmap != null) {
            // 如果传入了bitmap，只更新图片
            val metadata = MediaMetadataCompat.Builder(mediaSession.controller.metadata)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                .build()
            mediaSession.setMetadata(metadata)
        } else {
            // 如果没有传入bitmap，则更新文字信息，并异步加载图片
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration * 1000L)
                .build()
            mediaSession.setMetadata(metadata)

            serviceScope.launch {
                val loadedBitmap = loadBitmap(song.albumArtUrl)
                if (loadedBitmap != null) {
                    // 图片加载完成后，再次调用此函数，只更新图片
                    updateMediaMetadata(song, loadedBitmap)
                    // 再次更新通知，显示封面
                    updateNotification()
                }
            }
        }
    }

    private suspend fun loadBitmap(url: String?): Bitmap? {
        if (url == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val loader = ImageLoader(this@MusicService)
                val request = ImageRequest.Builder(this@MusicService)
                    .data(BASE_URL + url)
                    .allowHardware(false)
                    .build()
                (loader.execute(request) as? coil.request.SuccessResult)?.drawable?.let {
                    (it as? android.graphics.drawable.BitmapDrawable)?.bitmap
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun createNotification(): Notification {
        val channelId = "music_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 【修正】提高通知渠道的重要性，有助于系统优先显示
            val channel = NotificationChannel(channelId, "Music Player", NotificationManager.IMPORTANCE_DEFAULT)
            channel.setSound(null, null) // 无声通知
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val controller = mediaSession.controller
        val mediaMetadata = controller.metadata
        val description = mediaMetadata?.description

        val builder = NotificationCompat.Builder(this, channelId).apply {
            setContentTitle(description?.title)
            setContentText(description?.subtitle)
            setLargeIcon(description?.iconBitmap)

            val sessionActivity = controller.sessionActivity ?: run {
                val openAppIntent = Intent(this@MusicService, MainActivity::class.java)
                PendingIntent.getActivity(this@MusicService, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            setContentIntent(sessionActivity)

            setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this@MusicService, PlaybackStateCompat.ACTION_STOP))
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setSmallIcon(R.drawable.ic_music_note)
            addAction(NotificationCompat.Action(R.drawable.ic_skip_previous, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this@MusicService, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)))
            addAction(NotificationCompat.Action(if (_isPlaying.value) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (_isPlaying.value) "Pause" else "Play", MediaButtonReceiver.buildMediaButtonPendingIntent(this@MusicService, PlaybackStateCompat.ACTION_PLAY_PAUSE)))
            addAction(NotificationCompat.Action(R.drawable.ic_skip_next, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this@MusicService, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)))

            // 【修正】这是让UI变漂亮的关键
            setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    // 在紧凑视图（例如某些系统UI的折叠状态）中，显示所有三个按钮
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (_currentSong.value != null) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1, createNotification())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaSession.release()
        serviceJob.cancel()
    }
}