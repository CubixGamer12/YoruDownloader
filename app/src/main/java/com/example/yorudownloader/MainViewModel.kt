package com.example.yorudownloader

import android.app.Application
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Shared preferences for persistent settings storage
    private val prefs = application.getSharedPreferences("yoru_prefs", Context.MODE_PRIVATE)

    // UI state for the main download form and status
    var url by mutableStateOf("")
    var isDownloading by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("")
    var selectedFormat by mutableStateOf(DownloadFormat.MP4)
    var videoTitle by mutableStateOf("")
    var videoThumbnail by mutableStateOf("")
    var videoDuration by mutableStateOf("")
    var videoSize by mutableStateOf("")
    
    // Search results management and pagination state
    var searchResults = mutableStateListOf<SearchResult>()
    var isSearching by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var canLoadMore by mutableStateOf(false)
    private var currentSearchQuery = ""
    private var nextSearchIndex = 1
    private val pageSize = 10

    // Download configuration for video and audio
    var selectedVideoQuality by mutableStateOf(VideoQuality.valueOf(prefs.getString("video_quality", "Best") ?: "Best"))
    var selectedAudioQuality by mutableStateOf(prefs.getString("audio_quality", "Best") ?: "Best")
    var downloadMetadata by mutableStateOf(prefs.getBoolean("download_metadata", true))
    var downloadThumbnail by mutableStateOf(prefs.getBoolean("download_thumbnail", true))
    var downloadSubtitles by mutableStateOf(prefs.getBoolean("download_subtitles", false))
    var useSponsorBlock by mutableStateOf(prefs.getBoolean("use_sponsorblock", false))
    var isPlaylist by mutableStateOf(prefs.getBoolean("is_playlist", false))
    var useCookies by mutableStateOf(prefs.getBoolean("use_cookies", false))
    var selectedLanguage by mutableStateOf(AppLanguage.valueOf(prefs.getString("app_language", AppLanguage.Auto.name) ?: AppLanguage.Auto.name))
    
    // UI appearance and theme settings
    var themeMode by mutableStateOf(ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.System.name) ?: ThemeMode.System.name))
    var isDynamicColorEnabled by mutableStateOf(prefs.getBoolean("dynamic_colors", true))
    
    // Storage and path management
    private val defaultDownloadPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YoruDownloader").absolutePath
    var customStoragePath by mutableStateOf(prefs.getString("custom_storage_path", defaultDownloadPath)?.takeIf { it.isNotEmpty() } ?: defaultDownloadPath)
    
    var selectedVideoFps by mutableStateOf(prefs.getString("selected_video_fps", "Auto") ?: "Auto")
    
    // Advanced download engine parameters
    var videoCodecPreference by mutableStateOf(VideoCodecPreference.valueOf(prefs.getString("video_codec_pref", VideoCodecPreference.Default.name) ?: VideoCodecPreference.Default.name))
    var concurrentFragments by mutableStateOf(prefs.getInt("concurrent_fragments", 3))
    var embedChapters by mutableStateOf(prefs.getBoolean("embed_chapters", true))
    var retriesCount by mutableStateOf(prefs.getInt("retries_count", 10))
    var wifiOnly by mutableStateOf(prefs.getBoolean("wifi_only", false))

    var showSettingsDialog by mutableStateOf(false)
    var lastDownloadedFile by mutableStateOf<String?>(null)
    var isEngineUpdating by mutableStateOf(false)
    var engineUpdateStatus by mutableStateOf("")

    private var currentTaskId: String? = null
    
    // Service connection for background download operations
    private var downloadService: DownloadService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DownloadService.DownloadBinder
            downloadService = binder.getService()
            // Register callbacks to update ViewModel state from service
            downloadService?.onProgressUpdate = { p, line ->
                progress = p
                parseProgressLine(line)
            }
            downloadService?.onDownloadCompleted = { path ->
                isDownloading = false
                status = getApplication<Application>().getString(R.string.status_completed)
                progress = 1.0f
                lastDownloadedFile = path
            }
            downloadService?.onDownloadError = { error ->
                isDownloading = false
                status = if (error == getApplication<Application>().getString(R.string.status_cancelled)) {
                    error
                } else {
                    getApplication<Application>().getString(R.string.status_error, error)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
        }
    }

    init {
        // Bind to DownloadService when ViewModel is initialized
        val intent = Intent(application, DownloadService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        applyLanguage(selectedLanguage)
    }

    private fun parseProgressLine(line: String) {
        // Extract status and ETA from raw yt-dlp output
        if (line.contains("ETA")) {
            status = line.trim().substringAfter("[download]").trim()
        } else {
            status = line
        }
    }

    fun saveSettings(
        videoQuality: VideoQuality, 
        audioQuality: String,
        metadata: Boolean,
        thumbnail: Boolean,
        subtitles: Boolean,
        playlist: Boolean,
        sponsorBlock: Boolean,
        cookies: Boolean,
        language: AppLanguage,
        theme: ThemeMode,
        dynamic: Boolean,
        customPath: String,
        videoFps: String,
        codecPref: VideoCodecPreference,
        fragments: Int,
        chapters: Boolean,
        retries: Int,
        wifi: Boolean
    ) {
        // Update local state and persist settings to SharedPreferences
        selectedVideoQuality = videoQuality
        selectedAudioQuality = audioQuality
        downloadMetadata = metadata
        downloadThumbnail = thumbnail
        downloadSubtitles = subtitles
        isPlaylist = playlist
        useSponsorBlock = sponsorBlock
        useCookies = cookies
        themeMode = theme
        isDynamicColorEnabled = dynamic
        customStoragePath = customPath.ifEmpty { defaultDownloadPath }
        selectedVideoFps = videoFps
        videoCodecPreference = codecPref
        concurrentFragments = fragments
        embedChapters = chapters
        retriesCount = retries
        wifiOnly = wifi
        
        if (selectedLanguage != language) {
            selectedLanguage = language
            applyLanguage(language)
        }

        prefs.edit {
            putString("video_quality", videoQuality.name)
            putString("audio_quality", audioQuality)
            putBoolean("download_metadata", metadata)
            putBoolean("download_thumbnail", thumbnail)
            putBoolean("download_subtitles", subtitles)
            putBoolean("is_playlist", playlist)
            putBoolean("use_sponsorblock", sponsorBlock)
            putBoolean("use_cookies", cookies)
            putString("app_language", language.name)
            putString("theme_mode", theme.name)
            putBoolean("dynamic_colors", dynamic)
            putString("custom_storage_path", customStoragePath)
            putString("selected_video_fps", videoFps)
            putString("video_codec_pref", codecPref.name)
            putInt("concurrent_fragments", fragments)
            putBoolean("embed_chapters", chapters)
            putInt("retries_count", retries)
            putBoolean("wifi_only", wifi)
        }
    }

    private fun applyLanguage(language: AppLanguage) {
        // Apply app language dynamically using AppCompatDelegate
        val appLocales = if (language == AppLanguage.Auto) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.code)
        }
        viewModelScope.launch(Dispatchers.Main) {
            AppCompatDelegate.setApplicationLocales(appLocales)
        }
    }

    fun updateEngine() {
        // Trigger yt-dlp engine update process
        isEngineUpdating = true
        engineUpdateStatus = getApplication<Application>().getString(R.string.ytdlp_updating)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = YoutubeDL.getInstance().updateYoutubeDL(getApplication())
                engineUpdateStatus = getApplication<Application>().getString(R.string.ytdlp_updated, res?.name ?: "latest")
            } catch (e: Exception) {
                engineUpdateStatus = getApplication<Application>().getString(R.string.ytdlp_update_failed)
            } finally {
                isEngineUpdating = false
            }
        }
    }

    fun openFile(path: String) {
        // Open downloaded file using system Intent chooser
        val file = File(path)
        if (!file.exists()) return
        
        val context = getApplication<Application>()
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to open file", e)
        }
    }

    fun searchYoutube(query: String) {
        // Initialize new YouTube search task
        if (query.isBlank()) return
        currentSearchQuery = query
        nextSearchIndex = 1
        isSearching = true
        canLoadMore = false
        searchResults.clear()
        performSearch()
    }

    fun loadMoreSearchResults() {
        // Handle search results pagination
        if (isLoadingMore || !canLoadMore) return
        isLoadingMore = true
        performSearch()
    }

    private fun performSearch() {
        // Execute search query via yt-dlp (ytsearch)
        viewModelScope.launch {
            try {
                val request = YoutubeDLRequest("ytsearch100:$currentSearchQuery")
                request.addOption("--dump-json")
                request.addOption("--flat-playlist")
                request.addOption("--no-check-certificate")
                request.addOption("--playlist-start", nextSearchIndex)
                request.addOption("--playlist-end", nextSearchIndex + pageSize - 1)
                request.addOption("--extractor-args", "youtube:player_client=web")
                
                val output = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, null).out
                }
                
                val newResults = mutableListOf<SearchResult>()
                output.split("\n").filter { it.isNotBlank() }.forEach { jsonStr ->
                    try {
                        val json = JSONObject(jsonStr)
                        val title = json.optString("title", getApplication<Application>().getString(R.string.no_title))
                        val id = json.optString("id", "")
                        val videoUrl = if (id.isNotEmpty()) "https://www.youtube.com/watch?v=$id" 
                                      else json.optString("url", "")
                        
                        var thumbUrl = json.optString("thumbnail", "")
                        if (thumbUrl.isEmpty()) {
                            val thumbnails = json.optJSONArray("thumbnails")
                            if (thumbnails != null && thumbnails.length() > 0) {
                                thumbUrl = thumbnails.getJSONObject(thumbnails.length() - 1).optString("url", "")
                            }
                        }
                        
                        if (thumbUrl.isEmpty() && id.isNotEmpty()) {
                            thumbUrl = "https://img.youtube.com/vi/$id/mqdefault.jpg"
                        }
                        
                        val durationSeconds = json.optInt("duration", 0)
                        
                        if (videoUrl.isNotEmpty()) {
                            newResults.add(SearchResult(
                                title = title,
                                url = videoUrl,
                                thumbnail = thumbUrl,
                                duration = if (durationSeconds > 0) formatDuration(durationSeconds) else ""
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to parse search result line", e)
                    }
                }
                
                searchResults.addAll(newResults)
                canLoadMore = newResults.size >= pageSize
                nextSearchIndex += pageSize
                
            } catch (e: Exception) {
                Log.e("MainViewModel", "Search failed", e)
            } finally {
                isSearching = false
                isLoadingMore = false
            }
        }
    }

    fun setCookies(content: String) {
        // Save Netscape formatted cookies for authorized requests
        try {
            val file = File(getApplication<Application>().filesDir, "cookies.txt")
            FileOutputStream(file).use { it.write(content.toByteArray()) }
            useCookies = true
            prefs.edit { putBoolean("use_cookies", true) }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to save cookies", e)
        }
    }

    private var fetchJob: Job? = null
    fun fetchInfo() {
        // Fetch detailed video information before downloading
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            videoTitle = ""
            videoThumbnail = ""
            videoDuration = ""
            videoSize = ""
            status = ""
            return
        }
        
        extractYoutubeId(trimmedUrl)?.let { id ->
            videoThumbnail = "https://img.youtube.com/vi/$id/hqdefault.jpg"
        }

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            delay(800)
            status = getApplication<Application>().getString(R.string.status_fetching)
            try {
                val request = YoutubeDLRequest(trimmedUrl)
                request.addOption("--no-check-certificate")
                request.addOption("--no-mtime")
                request.addOption("--force-ipv4")
                request.addOption("--extractor-args", "youtube:player_client=web")
                
                if (useCookies) {
                    val cookieFile = File(getApplication<Application>().filesDir, "cookies.txt")
                    if (cookieFile.exists()) {
                        request.addOption("--cookies", cookieFile.absolutePath)
                    }
                }

                if (trimmedUrl.contains("list=")) {
                    request.addOption("--flat-playlist")
                    request.addOption("--playlist-items", "1")
                } else {
                    request.addOption("--no-playlist")
                }

                val info = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().getInfo(request)
                }
                
                videoTitle = info.title ?: getApplication<Application>().getString(R.string.no_title)
                if (info.thumbnail != null) videoThumbnail = info.thumbnail!!
                videoDuration = if (info.duration > 0) formatDuration(info.duration) else ""
                videoSize = if (info.fileSize > 0) formatFileSize(info.fileSize) else ""
                
                status = getApplication<Application>().getString(R.string.status_ready)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    status = getApplication<Application>().getString(R.string.status_error, e.localizedMessage)
                }
            }
        }
    }

    private fun formatFileSize(size: Long): String {
        // Convert file size in bytes to human-readable format
        val units = arrayOf("B", "KB", "MB", "GB")
        var s = size.toDouble()
        var unitIndex = 0
        while (s >= 1024 && unitIndex < units.size - 1) {
            s /= 1024
            unitIndex++
        }
        return String.format(Locale.getDefault(), "%.2f %s", s, units[unitIndex])
    }

    fun downloadVideo() {
        // Main logic to prepare request and start background download
        if (url.trim().isEmpty()) return

        // Verify WiFi connection if restricted in settings
        if (wifiOnly && !isWifiConnected()) {
            status = getApplication<Application>().getString(R.string.error_no_wifi)
            return
        }

        isDownloading = true
        progress = 0f
        status = getApplication<Application>().getString(R.string.status_initializing)
        lastDownloadedFile = null
        val taskId = UUID.randomUUID().toString()
        currentTaskId = taskId

        val targetDir = if (customStoragePath.isNotEmpty() && customStoragePath.startsWith("content://")) {
             File(Uri.parse(customStoragePath).path ?: defaultDownloadPath)
        } else if (customStoragePath.isNotEmpty()) {
             File(customStoragePath)
        } else {
             File(defaultDownloadPath)
        }
        
        if (!targetDir.exists()) targetDir.mkdirs()

        // Configure yt-dlp flags based on user preferences
        val request = YoutubeDLRequest(url.trim())
        request.addOption("--no-mtime")
        request.addOption("--no-check-certificate")
        request.addOption("--no-cache-dir")
        request.addOption("--force-ipv4")
        request.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        request.addOption("--extractor-args", "youtube:player_client=web")
        
        if (useCookies) {
            val cookieFile = File(getApplication<Application>().filesDir, "cookies.txt")
            if (cookieFile.exists()) request.addOption("--cookies", cookieFile.absolutePath)
        }
        
        if (downloadMetadata) request.addOption("--embed-metadata")
        if (downloadThumbnail) request.addOption("--embed-thumbnail")
        if (downloadSubtitles) {
            request.addOption("--write-subs")
            request.addOption("--all-subs")
            request.addOption("--embed-subs")
        }
        if (useSponsorBlock) request.addOption("--sponsorblock-remove", "all")
        if (!isPlaylist) request.addOption("--no-playlist") else request.addOption("--yes-playlist")
        
        request.addOption("--concurrent-fragments", concurrentFragments)
        request.addOption("--retries", retriesCount)
        if (embedChapters) request.addOption("--embed-chapters")

        val outputPath = "${targetDir.absolutePath}/%(title)s.${selectedFormat.ext}"
        if (selectedFormat.isAudio) {
            request.addOption("-x")
            request.addOption("--audio-format", selectedFormat.ext)
            val qualityValue = if (selectedAudioQuality == "Best") "0" else selectedAudioQuality.replace("K", "")
            request.addOption("--audio-quality", qualityValue)
            request.addOption("-o", outputPath)
        } else {
            var formatCode = selectedVideoQuality.formatCode
            
            // Apply video codec preferences
            when (videoCodecPreference) {
                VideoCodecPreference.H264 -> {
                    formatCode = formatCode.replace("bestvideo", "bestvideo[vcodec^=avc1]")
                }
                VideoCodecPreference.VP9 -> {
                    formatCode = formatCode.replace("bestvideo", "bestvideo[vcodec^=vp9]")
                }
                VideoCodecPreference.AV1 -> {
                    formatCode = formatCode.replace("bestvideo", "bestvideo[vcodec^=av01]")
                }
                VideoCodecPreference.Default -> { }
            }
            
            if (selectedVideoFps != "Auto") {
                formatCode = formatCode.replace("]", "[fps<=${selectedVideoFps}]]")
            }
            
            formatCode = "$formatCode/best"
            
            request.addOption("-f", formatCode)
            request.addOption("--merge-output-format", selectedFormat.ext)
            request.addOption("-o", outputPath)
        }

        // Pass download task to foreground service
        downloadService?.startDownload(request, taskId, targetDir.absolutePath + "/placeholder")
    }

    private fun isWifiConnected(): Boolean {
        // Check if current active network is WiFi
        val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun cancelDownload() {
        // Cancel the ongoing yt-dlp download process
        currentTaskId?.let { taskId ->
            viewModelScope.launch(Dispatchers.IO) {
                YoutubeDL.getInstance().destroyProcessById(taskId)
            }
        }
        isDownloading = false
        status = getApplication<Application>().getString(R.string.status_cancelled)
        progress = 0f
    }

    private fun extractYoutubeId(url: String): String? {
        // Extract 11-character video ID from various YouTube URL formats
        return try {
            if (url.contains("youtu.be/")) url.substringAfter("youtu.be/").substringBefore("?").substringBefore("&").take(11)
            else if (url.contains("v=")) url.substringAfter("v=").substringBefore("&").take(11)
            else if (url.contains("shorts/")) url.substringAfter("shorts/").substringBefore("?").substringBefore("&").take(11)
            else if (url.length == 11) url 
            else null
        } catch (e: Exception) { null }
    }

    private fun formatDuration(seconds: Int): String {
        // Format duration from seconds to H:MM:SS or MM:SS string
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    override fun onCleared() {
        super.onCleared()
        // Unbind service to avoid memory leaks when ViewModel is destroyed
        getApplication<Application>().unbindService(serviceConnection)
    }
}

// Support classes and enums for application configuration
data class SearchResult(val title: String, val url: String, val thumbnail: String, val duration: String)

enum class DownloadFormat(val ext: String, val isAudio: Boolean) {
    MP4("mp4", false),
    WebM("webm", false),
    MKV("mkv", false),
    MP3("mp3", true),
    M4A("m4a", true),
    Opus("opus", true),
    WAV("wav", true)
}

enum class VideoQuality(val formatCode: String) {
    Best("bestvideo+bestaudio/best"),
    P1080("bestvideo[height<=1080]+bestaudio/best[height<=1080]"),
    P720("bestvideo[height<=720]+bestaudio/best[height<=720]"),
    P480("bestvideo[height<=480]+bestaudio/best[height<=480]")
}

enum class AppLanguage(val code: String, val labelRes: Int) {
    Auto("", R.string.lang_auto),
    English("en", R.string.lang_en),
    Polish("pl", R.string.lang_pl),
    Japanese("ja", R.string.lang_ja)
}

enum class ThemeMode(val labelRes: Int) {
    System(R.string.theme_system),
    Light(R.string.theme_light),
    Dark(R.string.theme_dark)
}

enum class VideoCodecPreference(val labelRes: Int) {
    Default(R.string.codec_default),
    H264(R.string.codec_h264),
    VP9(R.string.codec_vp9),
    AV1(R.string.codec_av1)
}
