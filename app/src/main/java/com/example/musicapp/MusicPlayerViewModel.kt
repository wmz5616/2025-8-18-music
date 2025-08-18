// 文件路径: app/src/main/java/com/example/musicapp/MusicPlayerViewModel.kt
package com.example.musicapp

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

sealed interface SongsUiState {
    data class Success(val songs: List<Song>) : SongsUiState
    object Error : SongsUiState
    object Loading : SongsUiState
}

class MusicPlayerViewModel(private val application: Application) : AndroidViewModel(application) {

    val songsUiState = MutableStateFlow<SongsUiState>(SongsUiState.Loading)

    val currentSong = MusicService.currentSong
    val isPlaying = MusicService.isPlaying
    val currentTime = MusicService.currentTime
    val totalDuration = MusicService.totalDuration
    val playMode = MusicService.playMode

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics = _lyrics.asStateFlow()

    init {
        fetchSongs()

        // 【核心修复】监听当前歌曲的变化，自动获取新歌词
        viewModelScope.launch {
            currentSong.collect { song ->
                song?.let { fetchLyrics(it.lyricsUrl) }
            }
        }
    }

    private fun fetchSongs() {
        viewModelScope.launch {
            songsUiState.value = SongsUiState.Loading
            try {
                val songList = RetrofitInstance.api.getSongs()
                songsUiState.value = SongsUiState.Success(songList)

                val intent = Intent(application, MusicService::class.java).apply {
                    action = MusicService.ACTION_SET_PLAYLIST
                    putParcelableArrayListExtra("PLAYLIST", ArrayList(songList))
                }
                application.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                songsUiState.value = SongsUiState.Error
            }
        }
    }

    fun playSong(song: Song) {
        val intent = Intent(application, MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY
            putExtra("SONG_ID", song.id)
        }
        application.startService(intent)
        // fetchLyrics 的调用已移至 init 的监听器中，此处不再需要
    }

    private fun fetchLyrics(lyricsUrl: String?) {
        if (lyricsUrl == null) {
            _lyrics.value = listOf(LyricLine(0, "暂无歌词", 10000))
            return
        }
        viewModelScope.launch {
            _lyrics.value = listOf(LyricLine(0, "正在加载歌词...", 10000))
            try {
                val fullUrl = if (lyricsUrl.startsWith("http")) lyricsUrl else BASE_URL + lyricsUrl
                val lrcContent = withContext(Dispatchers.IO) {
                    URL(fullUrl).readText()
                }
                val parsedLyrics = LyricParser.parse(lrcContent)
                _lyrics.value = if (parsedLyrics.isEmpty()) listOf(LyricLine(0, "歌词解析失败", 10000)) else parsedLyrics
            } catch (e: Exception) {
                _lyrics.value = listOf(LyricLine(0, "歌词加载失败", 10000))
                e.printStackTrace()
            }
        }
    }

    fun togglePlayPause() {
        val intent = Intent(application, MusicService::class.java).apply { action = MusicService.ACTION_PAUSE }
        application.startService(intent)
    }

    fun playNextSong() {
        val intent = Intent(application, MusicService::class.java).apply { action = MusicService.ACTION_NEXT }
        application.startService(intent)
    }

    fun playPreviousSong() {
        val intent = Intent(application, MusicService::class.java).apply { action = MusicService.ACTION_PREVIOUS }
        application.startService(intent)
    }

    fun togglePlayMode() {
        val intent = Intent(application, MusicService::class.java).apply { action = MusicService.ACTION_TOGGLE_PLAY_MODE }
        application.startService(intent)
    }

    fun seekTo(timeInMillis: Long) {
        val intent = Intent(application, MusicService::class.java).apply {
            action = MusicService.ACTION_SEEK
            putExtra("SEEK_TO", timeInMillis)
        }
        application.startService(intent)
    }
}
