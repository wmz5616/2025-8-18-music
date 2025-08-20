package com.example.musicapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import com.example.musicapp.ui.theme.MusicAppTheme
import kotlinx.coroutines.launch

val SpotifyGreen = Color(0xFF1DB954)
val SpotifyDarkGray = Color(0xFF121212)
val SpotifyLightGray = Color(0xFF282828)
val SpotifyTextColor = Color.White

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) { /* Granted */ } else { /* Denied */ }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForNotificationPermission()
        setContent {
            MusicAppTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = SpotifyDarkGray) {
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

    val navController = rememberNavController()
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 0.dp, // 默认隐藏播放详情页
        sheetContent = {
            // 播放详情页现在是 BottomSheet 的内容
            currentSong?.let {
                NowPlayingScreen(
                    song = it,
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
        sheetShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        sheetContainerColor = SpotifyLightGray
    ) {
        // 主内容区域
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = "library", modifier = Modifier.fillMaxSize()) {
                composable("library") {
                    when (val state = songsUiState) {
                        is SongsUiState.Loading -> ShimmerLoadingList()
                        is SongsUiState.Success -> MusicLibraryScreen(
                            songs = state.songs,
                            currentSong = currentSong,
                            onSongClick = { song -> musicPlayerViewModel.playSong(song) },
                            onArtistClick = { artistName ->
                                navController.navigate("artistDetail/$artistName")
                            }
                        )
                        is SongsUiState.Error -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("加载失败，请检查网络和服务器。", color = SpotifyTextColor)
                        }
                    }
                }
                composable(
                    route = "artistDetail/{artistName}",
                    arguments = listOf(navArgument("artistName") { type = NavType.StringType })
                ) { backStackEntry ->
                    val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
                    val state = songsUiState
                    if (state is SongsUiState.Success) {
                        ArtistDetailScreen(
                            artistName = artistName,
                            allSongs = state.songs,
                            currentSong = currentSong,
                            onSongClick = { song -> musicPlayerViewModel.playSong(song) },
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }

            // 迷你播放器固定在底部
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
    val progress = if (totalDuration > 0) currentTime.toFloat() / totalDuration.toFloat() else 0f
    var isLiked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SpotifyLightGray)
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp) // 为底部导航栏或手势区域留出空间
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(model = song.albumArtUrl?.let { BASE_URL + it }, contentDescription = song.title, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop, placeholder = painterResource(id = R.drawable.placeholder_album_art))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(text = song.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = SpotifyTextColor)
                Text(text = song.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.LightGray)
            }
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(onClick = { isLiked = !isLiked }) {
                Icon(painter = painterResource(id = if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border), contentDescription = "Like", tint = if (isLiked) SpotifyGreen else Color.LightGray)
            }
            IconButton(onClick = onPlayPauseClick) {
                Icon(painter = painterResource(id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow), contentDescription = "Play/Pause", modifier = Modifier.size(28.dp), tint = SpotifyTextColor)
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .padding(horizontal = 8.dp),
            color = SpotifyTextColor,
            trackColor = Color.DarkGray
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLibraryScreen(
    songs: List<Song>,
    currentSong: Song?,
    onSongClick: (Song) -> Unit,
    onArtistClick: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("你的音乐库", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = SpotifyTextColor)
            )
        },
        containerColor = SpotifyDarkGray // 主背景色
    ) { paddingValues ->
        // 为底部的迷你播放器留出空间
        val bottomPadding = if (currentSong != null) 80.dp else 0.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            items(songs, key = { it.id }) { song ->
                SongListItem(
                    song = song,
                    isCurrentlyPlaying = song.id == currentSong?.id,
                    onClick = { onSongClick(song) },
                    onArtistClick = { onArtistClick(song.artist) }
                )
            }
        }
    }
}

@Composable
fun SongListItem(
    song: Song,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    onArtistClick: () -> Unit,
    showArtistName: Boolean = true
) {
    val titleColor = if (isCurrentlyPlaying) SpotifyGreen else SpotifyTextColor
    val artistColor = if (isCurrentlyPlaying) SpotifyGreen.copy(alpha = 0.7f) else Color.LightGray
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(model = song.albumArtUrl?.let { BASE_URL + it }, contentDescription = song.title, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop, placeholder = painterResource(id = R.drawable.placeholder_album_art))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(text = song.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = titleColor)
            if (showArtistName) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = artistColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onArtistClick)
                )
            }
        }
        Icon(painter = painterResource(id = R.drawable.ic_more_vert), contentDescription = "More options", tint = Color.LightGray)
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
    var showLyrics by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val currentLyricIndex by remember(lyrics, currentTime) {
        derivedStateOf { lyrics.indexOfLast { it.startTime <= currentTime * 1000L }.coerceAtLeast(0) }
    }
    LaunchedEffect(currentLyricIndex) {
        if (lyrics.isNotEmpty() && currentLyricIndex >= 0) {
            listState.animateScrollToItem(index = currentLyricIndex, scrollOffset = -250)
        }
    }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val isInitialComposition = remember { mutableStateOf(true) }

    LaunchedEffect(playMode) {
        if (isInitialComposition.value) {
            isInitialComposition.value = false
        } else {
            val message = when (playMode) {
                PlayMode.SEQUENTIAL -> "顺序播放"
                PlayMode.SHUFFLE -> "随机播放"
                PlayMode.REPEAT_ONE -> "单曲循环"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCollapse) { Icon(painter = painterResource(id = R.drawable.ic_expand_more), contentDescription = "Collapse", tint = SpotifyTextColor) }
            Text(text = "正在播放", color = SpotifyTextColor, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { /* TODO */ }) { Icon(painter = painterResource(id = R.drawable.ic_more_vert), contentDescription = "More", tint = SpotifyTextColor) }
        }
        Spacer(modifier = Modifier.weight(1f))
        Crossfade(
            targetState = showLyrics,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showLyrics = !showLyrics },
            label = "AlbumArt/Lyrics Crossfade",
            animationSpec = tween(500)
        ) { isShowingLyrics ->
            if (isShowingLyrics) {
                Box(modifier = Modifier.background(Color.Black.copy(alpha = 0.5f))) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item { Spacer(modifier = Modifier.height(150.dp)) }
                        itemsIndexed(lyrics) { index, line ->
                            val color by animateColorAsState(targetValue = if (index == currentLyricIndex) SpotifyTextColor else Color.Gray, label = "lyric color")
                            val fontSize by animateDpAsState(targetValue = if (index == currentLyricIndex) 20.dp else 18.dp, label = "font size")
                            Text(text = line.text, color = color, fontSize = fontSize.value.sp, textAlign = TextAlign.Center, fontWeight = if (index == currentLyricIndex) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp))
                        }
                        item { Spacer(modifier = Modifier.height(150.dp)) }
                    }
                }
            } else {
                AsyncImage(model = song.albumArtUrl?.let { BASE_URL + it }, contentDescription = song.title, modifier = Modifier.fillMaxSize().shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop, placeholder = painterResource(id = R.drawable.placeholder_album_art))
            }
        }
        Spacer(modifier = Modifier.height(40.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SpotifyTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = song.artist, fontSize = 16.sp, color = Color.LightGray, modifier = Modifier.padding(top = 4.dp))
            }
            var isLiked by remember { mutableStateOf(false) }
            val scale = remember { Animatable(1f) }
            LaunchedEffect(isLiked) {
                if(isLiked) {
                    scale.animateTo(targetValue = 1.3f, animationSpec = tween(durationMillis = 100, easing = LinearOutSlowInEasing))
                    scale.animateTo(targetValue = 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                }
            }
            IconButton(onClick = { isLiked = !isLiked }) {
                Icon(painter = painterResource(id = if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border), contentDescription = "Like", tint = if(isLiked) SpotifyGreen else Color.LightGray, modifier = Modifier.size(28.dp).scale(scale.value))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(value = if (totalDuration > 0) currentTime.toFloat() / totalDuration.toFloat() else 0f, onValueChange = { newPosition -> val newTimeInMillis = (newPosition * totalDuration * 1000).toLong(); onSeek(newTimeInMillis) }, colors = SliderDefaults.colors(thumbColor = SpotifyTextColor, activeTrackColor = SpotifyTextColor, inactiveTrackColor = Color.Gray))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(currentTime), fontSize = 12.sp, color = Color.LightGray)
                Text(formatTime(totalDuration), fontSize = 12.sp, color = Color.LightGray)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val activeColor = SpotifyGreen
            val inactiveColor = Color.LightGray
            IconButton(onClick = onTogglePlayMode) { Icon(painter = painterResource(id = R.drawable.ic_shuffle), contentDescription = "Shuffle", tint = if (playMode == PlayMode.SHUFFLE) activeColor else inactiveColor) }
            IconButton(onClick = { onPreviousClick(); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) { Icon(painter = painterResource(id = R.drawable.ic_skip_previous), contentDescription = "Previous", modifier = Modifier.size(40.dp), tint = SpotifyTextColor) }
            IconButton(onClick = { onPlayPauseClick(); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }, modifier = Modifier.size(64.dp).background(SpotifyTextColor, RoundedCornerShape(50))) { Icon(painter = painterResource(id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow), contentDescription = "Play/Pause", modifier = Modifier.size(36.dp), tint = Color.Black) }
            IconButton(onClick = { onNextClick(); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) { Icon(painter = painterResource(id = R.drawable.ic_skip_next), contentDescription = "Next", modifier = Modifier.size(40.dp), tint = SpotifyTextColor) }
            IconButton(onClick = onTogglePlayMode) { Icon(painter = painterResource(id = R.drawable.ic_repeat), contentDescription = "Repeat", tint = if (playMode == PlayMode.REPEAT_ONE) activeColor else inactiveColor) }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer transition")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer translation"
    )
    val brush = Brush.linearGradient(
        colors = listOf(SpotifyLightGray, SpotifyLightGray.copy(alpha = 0.5f), SpotifyLightGray),
        start = Offset.Zero,
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )
    this.background(brush)
}

@Composable
fun SongListItemPlaceholder() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).shimmer())
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth(0.7f).height(20.dp).clip(RoundedCornerShape(4.dp)).shimmer())
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth(0.4f).height(16.dp).clip(RoundedCornerShape(4.dp)).shimmer())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShimmerLoadingList() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("你的音乐库", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = SpotifyTextColor)
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            items(10) {
                SongListItemPlaceholder()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    allSongs: List<Song>,
    currentSong: Song?,
    onSongClick: (Song) -> Unit,
    onNavigateBack: () -> Unit
) {
    val artistSongs = remember(artistName, allSongs) {
        allSongs.filter { it.artist == artistName }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = artistName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = SpotifyTextColor,
                    navigationIconContentColor = SpotifyTextColor
                )
            )
        },
        containerColor = SpotifyDarkGray
    ) { paddingValues ->
        val bottomPadding = if (currentSong != null) 80.dp else 0.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            items(artistSongs, key = { it.id }) { song ->
                SongListItem(
                    song = song,
                    isCurrentlyPlaying = song.id == currentSong?.id,
                    onClick = { onSongClick(song) },
                    onArtistClick = {},
                    showArtistName = false
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun DefaultPreview() {
    MusicAppTheme(darkTheme = true) {
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