// 文件路径: app/src/main/java/com/example/musicapp/MusicService.kt
package com.example.musicapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

class MusicService : Service(), MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener {

    private var mediaPlayer: MediaPlayer? = null
    private var playlist: List<Song> = emptyList()
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var timeUpdateJob: Job? = null

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_PLAYLIST -> {
                playlist = intent.getParcelableArrayListExtra("PLAYLIST") ?: emptyList()
            }
            ACTION_PLAY -> {
                val songId = intent.getIntExtra("SONG_ID", -1)
                playlist.find { it.id == songId }?.let { playSong(it) }
            }
            ACTION_PAUSE -> togglePlayPause()
            ACTION_NEXT -> playNextSong()
            ACTION_PREVIOUS -> playPreviousSong()
            ACTION_TOGGLE_PLAY_MODE -> togglePlayMode()
            ACTION_SEEK -> {
                val time = intent.getLongExtra("SEEK_TO", 0)
                seekTo(time)
            }
        }
        return START_NOT_STICKY
    }

    private fun playSong(song: Song) {
        song.streamUrl?.let { streamUrl ->
            mediaPlayer?.release()
            _currentSong.value = song
            _isPlaying.value = false
            _totalDuration.value = song.duration
            _currentTime.value = 0

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                try {
                    val fullStreamUrl = if (streamUrl.startsWith("http")) streamUrl else BASE_URL + streamUrl
                    val headers = mutableMapOf<String, String>()
                    headers["ngrok-skip-browser-warning"] = "true"
                    headers["User-Agent"] = "MusicApp/1.0"
                    setDataSource(applicationContext, Uri.parse(fullStreamUrl), headers)
                    setOnPreparedListener(this@MusicService)
                    setOnErrorListener(this@MusicService)
                    setOnCompletionListener { playNextSong() }
                    isLooping = _playMode.value == PlayMode.REPEAT_ONE
                    prepareAsync()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            startForeground(1, createNotification())
        } ?: run {
            Log.e("MusicService", "Song streamUrl is null, cannot play.")
        }
    }

    override fun onPrepared(mp: MediaPlayer?) {
        _totalDuration.value = mp?.duration?.div(1000) ?: 0
        mp?.start()
        _isPlaying.value = true
        startTimeUpdates()
        updateNotification()
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        _isPlaying.value = false
        mediaPlayer?.reset()
        return true
    }

    private fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
                stopForeground(false)
            } else {
                if (_currentTime.value > 0) {
                    it.start()
                    _isPlaying.value = true
                    startForeground(1, createNotification())
                } else {
                    _currentSong.value?.let { song -> playSong(song) }
                }
            }
            updateNotification()
        }
    }

    private fun playNextSong() {
        _currentSong.value?.let { current ->
            val nextSong = when (_playMode.value) {
                PlayMode.SEQUENTIAL -> {
                    val currentIndex = playlist.indexOf(current)
                    if (playlist.isNotEmpty()) playlist[(currentIndex + 1) % playlist.size] else null
                }
                PlayMode.SHUFFLE -> {
                    playlist.filter { it.id != current.id }.randomOrNull()
                }
                PlayMode.REPEAT_ONE -> current
            }
            nextSong?.let { playSong(it) }
        }
    }

    private fun playPreviousSong() {
        _currentSong.value?.let { current ->
            val currentIndex = playlist.indexOf(current)
            val previousIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
            if (playlist.isNotEmpty()) playSong(playlist[previousIndex])
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
        mediaPlayer?.let {
            val safeSeekPosition = timeInMillis.coerceAtMost((_totalDuration.value * 1000).toLong())
            it.seekTo(safeSeekPosition.toInt())
            _currentTime.value = (safeSeekPosition / 1000).toInt()
        }
    }

    private fun startTimeUpdates() {
        timeUpdateJob?.cancel()
        timeUpdateJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && _isPlaying.value) {
                try {
                    _currentTime.value = mediaPlayer?.currentPosition?.div(1000) ?: _currentTime.value
                } catch (e: IllegalStateException) {
                    // Ignored
                }
                // 【核心改动】提高更新频率，让歌词滚动更丝滑
                delay(200)
            }
        }
    }

    private fun createNotification(): Notification {
        val channelId = "music_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Music Player", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val song = _currentSong.value
        val isPlaying = _isPlaying.value

        val prevIntent = PendingIntent.getService(this, 10, Intent(this, MusicService::class.java).setAction(ACTION_PREVIOUS), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pauseIntent = PendingIntent.getService(this, 11, Intent(this, MusicService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nextIntent = PendingIntent.getService(this, 12, Intent(this, MusicService::class.java).setAction(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val largeIcon = BitmapFactory.decodeResource(resources, R.drawable.placeholder_album_art)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(song?.title)
            .setContentText(song?.artist)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(largeIcon)
            .addAction(R.drawable.ic_skip_previous, "Previous", prevIntent)
            .addAction(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow, if (isPlaying) "Pause" else "Play", pauseIntent)
            .addAction(R.drawable.ic_skip_next, "Next", nextIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(1, createNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        serviceJob.cancel()
    }
}
