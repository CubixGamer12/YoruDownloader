package com.example.yorudownloader

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class YoruApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Initialize YoutubeDL
                YoutubeDL.getInstance().init(this@YoruApp)
                // Initialize FFmpeg - critical for merging video and audio
                FFmpeg.getInstance().init(this@YoruApp)
                
                // Update yt-dlp to latest version to fix extraction issues
                val updateResult = YoutubeDL.getInstance().updateYoutubeDL(this@YoruApp)
                Log.d("YoruApp", "YoutubeDL updated: ${updateResult?.name}")
            } catch (e: Exception) {
                Log.e("YoruApp", "Failed to initialize/update YoutubeDL or FFmpeg", e)
            }
        }
    }
}
