package com.example.yorudownloader

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class DownloadService : Service() {
    // Coroutine scope for asynchronous operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Binder for activity-service communication
    private val binder = DownloadBinder()
    
    // Notification channel and ID configuration
    private val CHANNEL_ID = "download_channel"
    private val NOTIFICATION_ID = 1001
    // Flag to track if the download process is finishing
    private var isFinishingDownload = false
    private val notificationLock = Any()
    // Task ID for the current yt-dlp process
    private var currentTaskId: String? = null

    companion object {
        // Action to cancel download from notification
        const val ACTION_CANCEL_DOWNLOAD = "com.example.yorudownloader.CANCEL_DOWNLOAD"
    }

    // Callbacks for UI updates
    var onProgressUpdate: ((Float, String) -> Unit)? = null
    var onDownloadCompleted: ((String) -> Unit)? = null
    var onDownloadError: ((String) -> Unit)? = null

    inner class DownloadBinder : Binder() {
        // Returns the service instance
        fun getService(): DownloadService = this@DownloadService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle cancellation command from notification intent
        if (intent?.action == ACTION_CANCEL_DOWNLOAD) {
            cancelDownload()
        }
        return START_NOT_STICKY
    }

    private fun cancelDownload() {
        // Destroy the active yt-dlp process by task ID
        currentTaskId?.let { taskId ->
            serviceScope.launch(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().destroyProcessById(taskId)
                } catch (e: Exception) {
                    Log.e("DownloadService", "Error cancelling download", e)
                }
                launch(Dispatchers.Main) {
                    onDownloadError?.invoke(getString(R.string.status_cancelled))
                    cleanupAndStop()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize notification channel for Android O+
        createNotificationChannel()
    }

    fun startDownload(request: YoutubeDLRequest, taskId: String, outputPath: String) {
        currentTaskId = taskId
        synchronized(notificationLock) {
            isFinishingDownload = false
        }
        
        // Start service in foreground mode
        val notification = createNotification(getString(R.string.status_initializing), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            try {
                var lastProgressUpdate = 0L
                // Execute download process via YoutubeDL library
                YoutubeDL.getInstance().execute(request, taskId) { progress, _, line ->
                    val p = if (progress >= 0) progress / 100f else 0f
                    
                    val currentTime = System.currentTimeMillis()
                    // Throttling notification updates (every 1s)
                    if (currentTime - lastProgressUpdate > 1000) {
                        updateNotification(getString(R.string.status_downloading), (p * 100).toInt())
                        lastProgressUpdate = currentTime
                    }
                    
                    launch(Dispatchers.Main) {
                        onProgressUpdate?.invoke(p, line ?: "")
                    }
                }
                
                launch(Dispatchers.Main) {
                    // Try to find the downloaded file and show completion notification
                    val downloadedFile = findDownloadedFile(outputPath)
                    onDownloadCompleted?.invoke(downloadedFile?.absolutePath ?: outputPath)
                    
                    if (downloadedFile != null) {
                        showCompletionNotification(downloadedFile)
                    }
                    
                    cleanupAndStop()
                }
            } catch (e: Exception) {
                Log.e("DownloadService", "Download failed", e)
                launch(Dispatchers.Main) {
                    if (currentTaskId != null) {
                        onDownloadError?.invoke(e.localizedMessage ?: "Unknown error")
                    }
                    cleanupAndStop()
                }
            }
        }
    }

    private fun cleanupAndStop() {
        synchronized(notificationLock) {
            isFinishingDownload = true
            currentTaskId = null
            
            // Exit foreground state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            
            // Explicitly remove the active notification
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        }
        
        stopSelf()
    }

    private fun findDownloadedFile(basePath: String): File? {
        // Logic to locate the downloaded file based on path and metadata
        val file = File(basePath)
        if (file.exists()) return file
        val dir = file.parentFile ?: return null
        return dir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String, progress: Int): Notification {
        // Building notification with progress bar and cancel action
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel), cancelPendingIntent)
            .build()
    }

    private fun showCompletionNotification(file: File) {
        // Display notification after successful download completion
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            val uri = FileProvider.getUriForFile(this@DownloadService, "${packageName}.fileprovider", file)
            setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 1, installIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.status_completed))
            .setContentText(file.name)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun updateNotification(content: String, progress: Int) {
        // Update the existing progress notification
        synchronized(notificationLock) {
            if (isFinishingDownload) return
            val notification = createNotification(content, progress)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release resources and cancel coroutine scope
        serviceScope.cancel()
    }
}
