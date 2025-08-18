// 文件路径: app/src/main/java/com/example/musicapp/ApiService.kt
package com.example.musicapp

import retrofit2.http.GET

// 【极其重要】请将这里的 URL 替换成你自己的 ngrok 静态域地址！
const val BASE_URL = "https://rare-ladybug-secondly.ngrok-free.app"

interface ApiService {
    @GET("songs")
    suspend fun getSongs(): List<Song>
}
