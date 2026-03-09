package com.example.yorudownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.yorudownloader.ui.theme.YoruDownloaderTheme

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        enableEdgeToEdge()
        setContent {
            val isDarkTheme = when (viewModel.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            key(viewModel.selectedLanguage, viewModel.themeMode, viewModel.isDynamicColorEnabled) {
                YoruDownloaderTheme(
                    darkTheme = isDarkTheme,
                    dynamicColor = viewModel.isDynamicColorEnabled
                ) {
                    val context = LocalContext.current
                    var hasNotificationPermission by remember {
                        mutableStateOf(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            } else true
                        )
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                        onResult = { isGranted -> hasNotificationPermission = isGranted }
                    )

                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    MainScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                viewModel.url = it
                viewModel.fetchInfo()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        if (viewModel.showSettingsDialog) {
            SettingsDialog(viewModel)
        }
        
        if (viewModel.showPreview && viewModel.videoPreviewUrl != null) {
            VideoPreviewDialog(
                url = viewModel.videoPreviewUrl!!,
                onDismiss = { viewModel.showPreview = false }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box {
                        OutlinedTextField(
                            value = viewModel.url,
                            onValueChange = {
                                viewModel.url = it
                                if (it.startsWith("http")) {
                                    viewModel.fetchInfo()
                                    viewModel.searchSuggestions.clear()
                                } else {
                                    viewModel.fetchSuggestions(it)
                                }
                            },
                            placeholder = { Text(stringResource(R.string.search_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                Row {
                                    if (viewModel.url.isNotEmpty()) {
                                        IconButton(onClick = {
                                            if (!viewModel.url.startsWith("http")) {
                                                viewModel.searchYoutube(viewModel.url)
                                            } else {
                                                viewModel.url = ""
                                            }
                                        }) {
                                            Icon(if (viewModel.url.startsWith("http")) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            enabled = !viewModel.isDownloading,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (viewModel.url.isNotEmpty()) {
                                        if (!viewModel.url.startsWith("http")) {
                                            viewModel.searchYoutube(viewModel.url)
                                        } else {
                                            viewModel.fetchInfo()
                                        }
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        )

                        // Suggestions Dropdown
                        if (viewModel.searchSuggestions.isNotEmpty() && !viewModel.url.startsWith("http") && !viewModel.isDownloading) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 64.dp)
                                    .heightIn(max = 250.dp),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                LazyColumn {
                                    items(viewModel.searchSuggestions) { suggestion ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    viewModel.url = suggestion
                                                    viewModel.searchYoutube(suggestion)
                                                }
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                            Text(suggestion, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.format), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.video), style = MaterialTheme.typography.labelSmall)
                        }

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val videoFormats = DownloadFormat.entries.filter { !it.isAudio }
                            videoFormats.forEachIndexed { index, format ->
                                SegmentedButton(
                                    selected = viewModel.selectedFormat == format,
                                    onClick = { viewModel.selectedFormat = format },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = videoFormats.size),
                                    label = { Text(format.name, fontSize = 11.sp) },
                                    enabled = !viewModel.isDownloading
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.audio), style = MaterialTheme.typography.labelSmall)
                        }

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val audioFormats = DownloadFormat.entries.filter { it.isAudio }
                            audioFormats.forEachIndexed { index, format ->
                                SegmentedButton(
                                    selected = viewModel.selectedFormat == format,
                                    onClick = { viewModel.selectedFormat = format },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = audioFormats.size),
                                    label = { Text(format.name, fontSize = 11.sp) },
                                    enabled = !viewModel.isDownloading
                                )
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                if (viewModel.isSearching) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (viewModel.searchResults.isNotEmpty()) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(viewModel.searchResults) { result ->
                            SearchItem(result) {
                                viewModel.url = result.url
                                viewModel.fetchInfo()
                                viewModel.searchResults.clear()
                            }
                        }

                        if (viewModel.canLoadMore) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (viewModel.isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        TextButton(onClick = { viewModel.loadMoreSearchResults() }) {
                                            Text(stringResource(R.string.load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    AnimatedVisibility(
                        visible = viewModel.videoTitle.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        VideoInfoCard(viewModel)
                    }

                    if (viewModel.videoTitle.isEmpty() && !viewModel.isSearching) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.DownloadForOffline, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                Text(
                                    stringResource(R.string.no_video_selected),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (viewModel.isDownloading || viewModel.status.isNotEmpty()) {
                DownloadProgressCard(viewModel)
            }
        }
    }
}

@Composable
fun VideoInfoCard(viewModel: MainViewModel) {
    Box {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        modifier = Modifier
                            .size(120.dp, 68.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(enabled = viewModel.videoPreviewUrl != null) {
                                viewModel.showPreview = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = viewModel.videoThumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (viewModel.videoPreviewUrl != null) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.Black.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = viewModel.videoTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (viewModel.videoDuration.isNotEmpty()) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(viewModel.videoDuration) },
                                    icon = { Icon(Icons.Default.Timer, null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                            if (viewModel.videoSize.isNotEmpty()) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(viewModel.videoSize) },
                                    icon = { Icon(Icons.Default.Storage, null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = { viewModel.downloadVideo() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !viewModel.isDownloading,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.download), fontWeight = FontWeight.Bold)
                }
            }
        }
        IconButton(
            onClick = { viewModel.clearVideoInfo() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
        }
    }
}

@Composable
fun VideoPreviewDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            setBackgroundColor(0x00000000)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun DownloadProgressCard(viewModel: MainViewModel) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = viewModel.status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (viewModel.isDownloading) {
                    IconButton(onClick = { viewModel.cancelDownload() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                    }
                } else if (viewModel.lastDownloadedFile != null) {
                    IconButton(onClick = { viewModel.openFile(viewModel.lastDownloadedFile!!) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (viewModel.isDownloading) {
                LinearProgressIndicator(
                    progress = { viewModel.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
                Text(
                    text = "${(viewModel.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
fun SearchItem(result: SearchResult, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = result.thumbnail,
                contentDescription = null,
                modifier = Modifier.size(100.dp, 56.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = result.duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(viewModel: MainViewModel) {
    var tempVideoQuality by remember { mutableStateOf(viewModel.selectedVideoQuality) }
    var tempAudioQuality by remember { mutableStateOf(viewModel.selectedAudioQuality) }
    var tempMetadata by remember { mutableStateOf(viewModel.downloadMetadata) }
    var tempThumbnail by remember { mutableStateOf(viewModel.downloadThumbnail) }
    var tempSubtitles by remember { mutableStateOf(viewModel.downloadSubtitles) }
    var tempPlaylist by remember { mutableStateOf(viewModel.isPlaylist) }
    var tempSponsorBlock by remember { mutableStateOf(viewModel.useSponsorBlock) }
    var tempCookies by remember { mutableStateOf(viewModel.useCookies) }
    var tempLanguage by remember { mutableStateOf(viewModel.selectedLanguage) }
    var tempTheme by remember { mutableStateOf(viewModel.themeMode) }
    var tempDynamic by remember { mutableStateOf(viewModel.isDynamicColorEnabled) }
    var tempCustomPath by remember { mutableStateOf(viewModel.customStoragePath) }
    var tempVideoFps by remember { mutableStateOf(viewModel.selectedVideoFps) }
    var tempCodecPref by remember { mutableStateOf(viewModel.videoCodecPreference) }
    var tempConcurrentFragments by remember { mutableStateOf(viewModel.concurrentFragments.toFloat()) }
    var tempEmbedChapters by remember { mutableStateOf(viewModel.embedChapters) }
    var tempRetriesCount by remember { mutableStateOf(viewModel.retriesCount.toFloat()) }
    var tempWifiOnly by remember { mutableStateOf(viewModel.wifiOnly) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        R.string.general to Icons.Default.Settings,
        R.string.quality to Icons.Default.HighQuality,
        R.string.features to Icons.Default.LibraryAdd,
        R.string.advanced_quality to Icons.Default.Tune
    )

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let {
                tempCustomPath = it.toString()
            }
        }
    )

    var showCookiesInput by remember { mutableStateOf(false) }
    var cookiesText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = { viewModel.showSettingsDialog = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.showSettingsDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                viewModel.saveSettings(
                                    tempVideoQuality, tempAudioQuality,
                                    tempMetadata, tempThumbnail, tempSubtitles,
                                    tempPlaylist, tempSponsorBlock, tempCookies,
                                    tempLanguage, tempTheme, tempDynamic,
                                    tempCustomPath, tempVideoFps,
                                    tempCodecPref, tempConcurrentFragments.toInt(),
                                    tempEmbedChapters, tempRetriesCount.toInt(),
                                    tempWifiOnly
                                )
                                viewModel.showSettingsDialog = false
                            }
                        ) {
                            Text(stringResource(R.string.save), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                )

                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { TabRowDefaults.PrimaryIndicator(modifier = Modifier.tabIndicatorOffset(selectedTab)) }
                ) {
                    tabs.forEachIndexed { index, (titleRes, icon) ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge) },
                            icon = { Icon(icon, null, modifier = Modifier.size(20.dp)) }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        when (selectedTab) {
                            0 -> { // General
                                SettingsSection(title = stringResource(R.string.general), icon = Icons.Default.Language) {
                                    Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    QualitySelector(
                                        options = AppLanguage.entries.toList(),
                                        selectedOption = tempLanguage,
                                        onOptionSelected = { tempLanguage = it },
                                        label = { Text(stringResource(it.labelRes), maxLines = 1, fontSize = 11.sp) }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SettingsSwitchItem(
                                        title = stringResource(R.string.wifi_only_title),
                                        desc = stringResource(R.string.wifi_only_desc),
                                        icon = Icons.Default.Wifi,
                                        checked = tempWifiOnly,
                                        onCheckedChange = { tempWifiOnly = it }
                                    )
                                }

                                SettingsSection(title = stringResource(R.string.appearance), icon = Icons.Default.Palette) {
                                    Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    QualitySelector(
                                        options = ThemeMode.entries.toList(),
                                        selectedOption = tempTheme,
                                        onOptionSelected = { tempTheme = it },
                                        label = { Text(stringResource(it.labelRes), maxLines = 1, fontSize = 11.sp) }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SettingsSwitchItem(
                                        title = stringResource(R.string.dynamic_colors),
                                        desc = stringResource(R.string.dynamic_colors_desc),
                                        icon = Icons.Default.ColorLens,
                                        checked = tempDynamic,
                                        onCheckedChange = { tempDynamic = it }
                                    )
                                }

                                SettingsSection(title = stringResource(R.string.storage), icon = Icons.Default.FolderOpen) {
                                    Column(modifier = Modifier.fillMaxWidth().clickable { folderPickerLauncher.launch(null) }.padding(vertical = 4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(stringResource(R.string.custom_path), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                                val displayPath = if (tempCustomPath.startsWith("content://")) {
                                                    Uri.parse(tempCustomPath).path ?: tempCustomPath
                                                } else tempCustomPath
                                                Text(
                                                    text = stringResource(R.string.selected_folder_label, displayPath),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                            1 -> { // Quality
                                SettingsSection(title = stringResource(R.string.quality), icon = Icons.Default.HighQuality) {
                                    Text(stringResource(R.string.video_quality), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    QualitySelector(
                                        options = VideoQuality.entries.toList(),
                                        selectedOption = tempVideoQuality,
                                        onOptionSelected = { tempVideoQuality = it },
                                        label = {
                                            val label = if (it == VideoQuality.Best) stringResource(R.string.quality_best) else it.name.replace("P", "") + "p"
                                            Text(label, maxLines = 1, fontSize = 11.sp)
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(stringResource(R.string.audio_quality), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    val audioOptions = listOf("Best", "320K", "256K", "192K", "128K")
                                    QualitySelector(
                                        options = audioOptions,
                                        selectedOption = tempAudioQuality,
                                        onOptionSelected = { tempAudioQuality = it },
                                        label = {
                                            val label = if (it == "Best") stringResource(R.string.quality_best) else it
                                            Text(label, maxLines = 1, fontSize = 11.sp)
                                        }
                                    )
                                }
                                
                                SettingsSection(title = stringResource(R.string.advanced_quality), icon = Icons.Default.Tune) {
                                    Text(stringResource(R.string.video_fps), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    val fpsOptions = listOf("Auto", "30", "60")
                                    QualitySelector(
                                        options = fpsOptions,
                                        selectedOption = tempVideoFps,
                                        onOptionSelected = { tempVideoFps = it },
                                        label = { Text(it, fontSize = 11.sp) }
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(stringResource(R.string.video_codec), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    QualitySelector(
                                        options = VideoCodecPreference.entries.toList(),
                                        selectedOption = tempCodecPref,
                                        onOptionSelected = { tempCodecPref = it },
                                        label = { Text(stringResource(it.labelRes), fontSize = 11.sp) }
                                    )
                                }
                            }
                            2 -> { // Features
                                SettingsSection(title = stringResource(R.string.features), icon = Icons.Default.LibraryAdd) {
                                    SettingsSwitchItem(stringResource(R.string.metadata_title), stringResource(R.string.metadata_desc), Icons.Default.Info, tempMetadata) { tempMetadata = it }
                                    SettingsSwitchItem(stringResource(R.string.thumbnails_title), stringResource(R.string.thumbnails_desc), Icons.Default.Image, tempThumbnail) { tempThumbnail = it }
                                    SettingsSwitchItem(stringResource(R.string.subtitles_title), stringResource(R.string.subtitles_desc), Icons.Default.Subtitles, tempSubtitles) { tempSubtitles = it }
                                    SettingsSwitchItem(stringResource(R.string.sponsorblock_title), stringResource(R.string.sponsorblock_desc), Icons.Default.AdUnits, tempSponsorBlock) { tempSponsorBlock = it }
                                    SettingsSwitchItem(stringResource(R.string.playlists_title), stringResource(R.string.playlists_desc), Icons.AutoMirrored.Filled.PlaylistPlay, tempPlaylist) { tempPlaylist = it }
                                    SettingsSwitchItem(stringResource(R.string.embed_chapters), stringResource(R.string.embed_chapters_desc), Icons.Default.Bookmarks, tempEmbedChapters) { tempEmbedChapters = it }
                                }
                            }
                            3 -> { // Advanced
                                SettingsSection(title = stringResource(R.string.engine), icon = Icons.Default.Build) {
                                    Button(
                                        onClick = { viewModel.updateEngine() },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !viewModel.isEngineUpdating,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        if (viewModel.isEngineUpdating) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.SystemUpdate, null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.update_ytdlp))
                                        }
                                    }
                                    if (viewModel.engineUpdateStatus.isNotEmpty()) {
                                        Text(viewModel.engineUpdateStatus, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                    }
                                }

                                SettingsSection(title = stringResource(R.string.advanced_quality), icon = Icons.Default.Tune) {
                                    Text(stringResource(R.string.retries_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Slider(
                                            value = tempRetriesCount,
                                            onValueChange = { tempRetriesCount = it },
                                            valueRange = 0f..50f,
                                            steps = 49,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(tempRetriesCount.toInt().toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(stringResource(R.string.concurrent_fragments), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Slider(
                                            value = tempConcurrentFragments,
                                            onValueChange = { tempConcurrentFragments = it },
                                            valueRange = 1f..16f,
                                            steps = 14,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(tempConcurrentFragments.toInt().toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                SettingsSection(title = stringResource(R.string.cookies_title), icon = Icons.Default.VpnKey) {
                                    SettingsSwitchItem(
                                        title = stringResource(R.string.cookies_title),
                                        desc = stringResource(R.string.cookies_desc),
                                        icon = Icons.Default.VpnKey,
                                        checked = tempCookies,
                                        onCheckedChange = { tempCookies = it },
                                        action = if (tempCookies) {
                                            {
                                                TextButton(
                                                    onClick = { showCookiesInput = true },
                                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Text(stringResource(R.string.edit), style = MaterialTheme.typography.labelLarge)
                                                }
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCookiesInput) {
        AlertDialog(
            onDismissRequest = { showCookiesInput = false },
            title = { Text(stringResource(R.string.cookies_input_title)) },
            text = {
                OutlinedTextField(
                    value = cookiesText,
                    onValueChange = { cookiesText = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    placeholder = { Text(stringResource(R.string.cookies_input_hint)) }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setCookies(cookiesText)
                    showCookiesInput = false
                }) { Text(stringResource(R.string.save_cookies)) }
            },
            dismissButton = {
                TextButton(onClick = { showCookiesInput = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    desc: String,
    icon: ImageVector,
    checked: Boolean,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(desc, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (action != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    action()
                }
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> QualitySelector(options: List<T>, selectedOption: T, onOptionSelected: (T) -> Unit, label: @Composable (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selectedOption,
                onClick = { onOptionSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { label(option) }
            )
        }
    }
}

@Composable
fun FormatChip(selected: Boolean, onClick: () -> Unit, label: String, icon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    )
}
