package com.baconaa.folio.ui

import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.baconaa.folio.records.ContentItem
import kotlinx.coroutines.CancellationException

internal fun videoLabel(name: String, duration: Long?): String =
    if (duration == null) "视频 · $name" else "视频 · $name · ${formatAudioDuration(duration)}"

@Composable
internal fun VideoAttachment(item: ContentItem.Video, model: FolioViewModel) {
    var open by rememberSaveable(item.id.value) { mutableStateOf(false) }
    val preview by produceState<android.graphics.Bitmap?>(null, item.videoId) { value = model.videoThumbnail(item) }
    Column {
        Text(videoLabel(item.displayName, item.durationMillis))
        preview?.let {
            Image(it.asImageBitmap(), "视频预览", Modifier.fillMaxWidth().heightIn(max = 180.dp), contentScale = ContentScale.Fit)
        }
        TextButton(onClick = { model.stopPlayback(); open = true }, enabled = !model.state.recording) { Text("播放视频") }
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        VideoPlayer(item, model) { open = false }
    }
}

@Composable
private fun VideoPlayer(item: ContentItem.Video, model: FolioViewModel, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val player = remember(item.videoId) { ExoPlayer.Builder(context).build().apply { setAudioAttributes(AudioAttributes.DEFAULT, true) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var rendered by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { rendered = true }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onPlaybackStateChanged(state: Int) { ended = state == Player.STATE_ENDED }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlayerError(failure: PlaybackException) { error = "视频无法播放，请检查原件或重试。" }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        player.addListener(listener)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, attempt) {
        rendered = false
        error = null
        try {
            player.setMediaItem(MediaItem.fromUri(model.videoSource(item)))
            player.prepare()
            player.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "视频原件无法读取，请重试或重新选择视频。"
        }
    }
    Surface(shape = MaterialTheme.shapes.large) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(item.displayName, style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(factory = { SurfaceView(it).also(player::setVideoSurfaceView) }, modifier = Modifier.aspectRatio(aspect))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FlowRow {
                if (error != null) TextButton(onClick = { attempt++ }) { Text("重试播放") }
                else TextButton(enabled = rendered, onClick = {
                    if (ended) { player.seekTo(0); player.play() }
                    else if (playing) player.pause() else player.play()
                }) { Text(if (ended) "重新播放" else if (playing) "暂停视频" else "继续播放") }
                TextButton(onClick = close) { Text("关闭视频") }
            }
        }
    }
}
