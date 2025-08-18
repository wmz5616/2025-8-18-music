package com.example.musicapp

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

// 定义播放模式的枚举
enum class PlayMode {
    SEQUENTIAL, // 顺序播放
    SHUFFLE,    // 随机播放
    REPEAT_ONE  // 单曲循环
}

// 对应服务器 JSON 数据的模型
@Parcelize
data class Song(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String,
    @SerializedName("albumArtUrl") val albumArtUrl: String?,
    @SerializedName("streamUrl") val streamUrl: String?,
    @SerializedName("lyricsUrl") val lyricsUrl: String?,
    @SerializedName("duration") val duration: Int
) : Parcelable

// 用于表示一句解析后的歌词的数据类
data class LyricLine(
    val startTime: Long, // 开始时间 (毫秒)
    val text: String,    // 歌词文本
    val duration: Long   // 持续时间 (毫秒)
)
