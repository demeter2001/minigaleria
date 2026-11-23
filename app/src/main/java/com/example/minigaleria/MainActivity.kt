package com.example.minigaleria

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.ImageLoader
import coil.request.ImageRequest

enum class Kind(val label: String) { IMAGE("Imágenes"), VIDEO("Videos"), AUDIO("Audios") }
data class Media(val uri: Uri, val name: String, val kind: Kind)

fun loadMedia(ctx: Context, kind: Kind): List<Media> {
    val base = when (kind) {
        Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        Kind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }
    val out = mutableListOf<Media>()
    ctx.contentResolver.query(
        base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
        null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC"
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            out += Media(ContentUris.withAppendedId(base, id), c.getString(1) ?: "?", kind)
        }
    }
    return out
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val perms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.any { it }
    }
    LaunchedEffect(Unit) { launcher.launch(perms) }
    if (!granted) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Button(onClick = { launcher.launch(perms) }) { Text("Permitir acceso a archivos") }
        }
        return
    }
    var tab by remember { mutableStateOf(Kind.IMAGE) }
    var viewing by remember { mutableStateOf<Pair<List<Media>, Int>?>(null) }
    val items = remember(tab, granted) { loadMedia(ctx, tab) }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build() }

    viewing?.let { (list, idx) ->
        BackHandler { viewing = null }
        Viewer(list, idx, loader)
        return
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            Kind.values().forEach { k -> Tab(tab == k, { tab = k }, text = { Text(k.label) }) }
        }
        if (items.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Sin archivos") }
        else if (tab == Kind.AUDIO) LazyColumn {
            items(items.size) { i ->
                ListItem(
                    headlineContent = { Text(items[i].name, maxLines = 1) },
                    leadingContent = { Icon(Icons.Default.MusicNote, null) },
                    modifier = Modifier.clickable { viewing = items to i })
            }
        } else LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(2.dp)) {
            items(items.size) { i ->
                Box(Modifier.padding(2.dp).aspectRatio(1f).clip(MaterialTheme.shapes.small).clickable { viewing = items to i }) {
                    AsyncImage(
                        ImageRequest.Builder(ctx).data(items[i].uri).size(256).build(), null, loader,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (tab == Kind.VIDEO) Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.Center).size(36.dp), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(list: List<Media>, start: Int, loader: ImageLoader) {
    val pager = rememberPagerState(start) { list.size }
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
        val m = list[page]
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (m.kind == Kind.IMAGE) ZoomImage(m.uri, loader)
            else if (pager.currentPage == page) Player(m.uri)
        }
    }
}

@Composable
fun ZoomImage(uri: Uri, loader: ImageLoader) {
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    AsyncImage(uri, null, loader, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    off = if (scale > 1f) off + pan else androidx.compose.ui.geometry.Offset.Zero
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y))
}

@Composable
fun Player(uri: Uri) {
    val ctx = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView({ c -> PlayerView(c).apply { this.player = player; layoutParams = ViewGroup.LayoutParams(-1, -1) } }, Modifier.fillMaxSize())
}
