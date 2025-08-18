// 文件路径: app/src/main/java/com/example/musicapp/MainActivity.kt
package com.example.musicapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.musicapp.ui.theme.MusicAppTheme
import kotlinx.coroutines.launch

// 【新增】定义一个漂亮的绿色用于歌词高亮
val LyricGreen = Color(0xFF1DB954)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) { /* Granted */ } else { /* Denied */ }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForNotificationPermission()
        setContent {
            MusicAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MusicApp()
                }
            }
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicApp(musicPlayerViewModel: MusicPlayerViewModel = viewModel()) {
    val songsUiState by musicPlayerViewModel.songsUiState.collectAsState()
    val currentSong by musicPlayerViewModel.currentSong.collectAsState()
    val isPlaying by musicPlayerViewModel.isPlaying.collectAsState()
    val currentTime by musicPlayerViewModel.currentTime.collectAsState()
    val totalDuration by musicPlayerViewModel.totalDuration.collectAsState()
    val playMode by musicPlayerViewModel.playMode.collectAsState()
    val lyrics by musicPlayerViewModel.lyrics.collectAsState()

    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 0.dp,
        sheetContent = {
            currentSong?.let { song ->
                NowPlayingScreen(
                    song = song,
                    isPlaying = isPlaying,
                    currentTime = currentTime,
                    totalDuration = totalDuration,
                    playMode = playMode,
                    lyrics = lyrics,
                    onCollapse = { scope.launch { scaffoldState.bottomSheetState.partialExpand() } },
                    onPlayPauseClick = { musicPlayerViewModel.togglePlayPause() },
                    onNextClick = { musicPlayerViewModel.playNextSong() },
                    onPreviousClick = { musicPlayerViewModel.playPreviousSong() },
                    onSeek = { timeInMillis -> musicPlayerViewModel.seekTo(timeInMillis) },
                    onTogglePlayMode = { musicPlayerViewModel.togglePlayMode() }
                )
            }
        },
        sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surfaceVariant
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val state = songsUiState) {
                is SongsUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is SongsUiState.Success -> {
                    MusicLibraryScreen(
                        songs = state.songs,
                        currentSong = currentSong,
                        contentPadding = if (currentSong != null) PaddingValues(bottom = 80.dp) else PaddingValues(0.dp),
                        onSongClick = { song ->
                            musicPlayerViewModel.playSong(song)
                        }
                    )
                }
                is SongsUiState.Error -> Text("加载失败，请检查网络和服务器。", modifier = Modifier.align(Alignment.Center))
            }

            AnimatedVisibility(
                visible = currentSong != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                currentSong?.let { song ->
                    SpotifyMiniPlayer(
                        song = song,
                        isPlaying = isPlaying,
                        currentTime = currentTime,
                        totalDuration = totalDuration,
                        onPlayPauseClick = { musicPlayerViewModel.togglePlayPause() },
                        onClick = { scope.launch { scaffoldState.bottomSheetState.expand() } }
                    )
                }
            }
        }
    }
}

@Composable
fun SpotifyMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    currentTime: Int,
    totalDuration: Int,
    onPlayPauseClick: () -> Unit,
    onClick: () -> Unit
) {
    var isLiked by remember { mutableStateOf(false) }
    val progress = if (totalDuration > 0) currentTime.toFloat() / totalDuration.toFloat() else 0f

    Card(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = song.albumArtUrl?.let { BASE_URL + it },
                    contentDescription = song.title,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(id = R.drawable.placeholder_album_art)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(text = song.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    Text(text = song.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(onClick = { isLiked = !isLiked }) {
                    Icon(
                        painter = if (isLiked) painterResource(id = R.drawable.ic_favorite) else painterResource(id = R.drawable.ic_favorite_border),
                        contentDescription = "Like",
                        tint = if (isLiked) LyricGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPlayPauseClick) {
                    Icon(
                        painter = painterResource(id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLibraryScreen(songs: List<Song>, currentSong: Song?, contentPadding: PaddingValues, onSongClick: (Song) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("在线音乐", fontSize = 28.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = MaterialTheme.colorScheme.onBackground)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = contentPadding
        ) {
            items(songs) { song ->
                SongListItem(song = song, isCurrentlyPlaying = song.id == currentSong?.id, onClick = { onSongClick(song) })
            }
        }
    }
}

@Composable
fun SongListItem(song: Song, isCurrentlyPlaying: Boolean, onClick: () -> Unit) {
    val animatedBackgroundColor by animateColorAsState(targetValue = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "background color animation")
    val animatedTitleColor by animateColorAsState(targetValue = if (isCurrentlyPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, label = "title color animation")
    val animatedArtistColor by animateColorAsState(targetValue = if (isCurrentlyPlaying) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant, label = "artist color animation")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(animatedBackgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.albumArtUrl?.let { BASE_URL + it },
            contentDescription = song.title,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            placeholder = painterResource(id = R.drawable.placeholder_album_art)
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(text = song.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = animatedTitleColor)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = song.artist, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = animatedArtistColor)
        }
    }
}

fun formatTime(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%d:%02d".format(minutes, remainingSeconds)
}

@Composable
fun NowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    currentTime: Int,
    totalDuration: Int,
    playMode: PlayMode,
    lyrics: List<LyricLine>,
    onCollapse: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlayMode: () -> Unit
) {
    val listState = rememberLazyListState()
    var showLyrics by remember { mutableStateOf(false) }

    val currentLyricIndex by remember(lyrics, currentTime) {
        derivedStateOf {
            lyrics.indexOfLast { it.startTime <= currentTime * 1000L }
                .coerceAtLeast(0)
        }
    }

    LaunchedEffect(currentLyricIndex) {
        if (lyrics.isNotEmpty() && currentLyricIndex >= 0) {
            listState.animateScrollToItem(index = currentLyricIndex, scrollOffset = -250)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onCollapse) { Icon(painter = painterResource(id = R.drawable.ic_expand_more), contentDescription = "Collapse") }
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { /* TODO */ }) { Icon(painter = painterResource(id = R.drawable.ic_more_vert), contentDescription = "More") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Crossfade(
            targetState = showLyrics,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showLyrics = !showLyrics },
            label = "AlbumArt/Lyrics Crossfade",
            animationSpec = tween(500)
        ) { isShowingLyrics ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isShowingLyrics) {
                    Box {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            item { Spacer(modifier = Modifier.height(250.dp)) }
                            itemsIndexed(lyrics) { index, line ->
                                val animatedColor by animateColorAsState(
                                    targetValue = if (index == currentLyricIndex) LyricGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    animationSpec = tween(300), label = "lyric color"
                                )
                                val animatedFontSize by animateDpAsState(
                                    targetValue = if (index == currentLyricIndex) 20.dp else 16.dp,
                                    animationSpec = tween(300), label = "font size"
                                )

                                Text(
                                    text = line.text,
                                    color = animatedColor,
                                    fontSize = animatedFontSize.value.sp,
                                    textAlign = TextAlign.Center,
                                    fontWeight = if (index == currentLyricIndex) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                )
                            }
                            item { Spacer(modifier = Modifier.height(250.dp)) }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    )
                                )
                        )
                    }
                } else {
                    AsyncImage(
                        model = song.albumArtUrl?.let { BASE_URL + it },
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1f).clip(RoundedCornerShape(20.dp)).shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(id = R.drawable.placeholder_album_art)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(visible = !showLyrics) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (totalDuration > 0) currentTime.toFloat() / totalDuration.toFloat() else 0f,
                onValueChange = { newPosition ->
                    val newTimeInMillis = (newPosition * totalDuration * 1000).toLong()
                    onSeek(newTimeInMillis)
                }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(currentTime), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(totalDuration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shuffleColor = if (playMode == PlayMode.SHUFFLE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            IconButton(onClick = onTogglePlayMode) { Icon(painter = painterResource(id = R.drawable.ic_shuffle), contentDescription = "Shuffle", tint = shuffleColor) }
            IconButton(onClick = onPreviousClick) { Icon(painter = painterResource(id = R.drawable.ic_skip_previous), contentDescription = "Previous", modifier = Modifier.size(40.dp)) }
            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            ) {
                Icon(
                    painter = painterResource(id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            IconButton(onClick = onNextClick) { Icon(painter = painterResource(id = R.drawable.ic_skip_next), contentDescription = "Next", modifier = Modifier.size(40.dp)) }
            val repeatColor = if (playMode == PlayMode.REPEAT_ONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            IconButton(onClick = onTogglePlayMode) { Icon(painter = painterResource(id = R.drawable.ic_repeat), contentDescription = "Repeat", tint = repeatColor) }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    MusicAppTheme {
        val previewSong = Song(1, "预览歌曲", "预览艺术家", "", "", null, 180)
        NowPlayingScreen(
            song = previewSong,
            isPlaying = true,
            currentTime = 90,
            totalDuration = 180,
            playMode = PlayMode.SEQUENTIAL,
            lyrics = listOf(LyricLine(0, "这是预览歌词", 10000)),
            onCollapse = {},
            onPlayPauseClick = {},
            onNextClick = {},
            onPreviousClick = {},
            onSeek = {},
            onTogglePlayMode = {}
        )
    }
}
