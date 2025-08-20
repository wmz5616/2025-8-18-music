package com.example.musicapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.media.session.MediaButtonReceiver

class MediaServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 检查是否是媒体按钮事件
        if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
            // 创建一个明确指向我们服务的 Intent
            val serviceIntent = Intent(context, MusicService::class.java)
            // 将原始 Intent 的所有附加信息（包含了按键事件）都复制过去
            serviceIntent.putExtras(intent.extras ?: return)

            // 启动服务，确保它在处理事件前处于运行状态
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            return
        }
    }
}