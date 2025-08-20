package com.example.musicapp

import retrofit2.http.GET

const val BASE_URL = "https://rare-ladybug-secondly.ngrok-free.app"

interface ApiService {
    @GET("songs")
    suspend fun getSongs(): List<Song>
}