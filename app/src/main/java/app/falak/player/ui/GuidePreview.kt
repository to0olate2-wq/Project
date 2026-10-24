package app.falak.player.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.falak.player.FalakApp
import app.falak.player.data.Channel
import app.falak.player.data.Program
import app.falak.player.data.Repository
import java.util.Date

private enum class PreviewStatus { LOADING, PLAYING, FAILED }

/**
 * The guide's preview: the channel playing small above the guide, with what's on. Picking the same
 * channel again (or tapping the picture) opens it full screen.
 */
@Composable
fun GuidePreviewPane(
    ch: Channel,
    userAgent: String,
    programs: List<Program>?,
    now: Long,
    onOpen: () -> Unit,
) {
    val tv = isTv()
    val timeFormat = rememberTimeFormat()
    val current = programs?.firstOrNull { now >= it.start && now < it.stop }
    val next = programs?.firstOrNull { it.start >= (current?.stop ?: now) }
    val height: Dp = if (tv) 168.dp else 124.dp
    Row(
        Modifier.fillMaxWidth().height(height).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PreviewPicture(
            ch,
            userAgent,
            onOpen,
            Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Phones: no room for the label; the picture says it.
            if (tv) Kicker("Preview", color = Signal)
            Text(
                ch.name,
                fontFamily = Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (tv) 21.sp else 16.sp,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current != null) {
                Text(
                    current.title,
                    fontFamily = Sans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (tv) 15.sp else 13.sp,
                    color = Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${timeFormat.format(Date(current.start))}–${timeFormat.format(Date(current.stop))}" +
                        (next?.let { " · Next: ${it.title}" } ?: ""),
                    fontFamily = Sans,
                    fontSize = 12.sp,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tv && current.desc.isNotBlank()) {
                    Text(current.desc, fontFamily = Sans, fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (tv) "Press OK again for full screen · Back to stop" else "Tap again for full screen",
                fontFamily = Sans,
                fontSize = 11.sp,
                color = Faint,
                maxLines = 1,
            )
        }
    }
}

/** A channel playing small, without controls; a tap opens it (the guide's preview, the mini player). */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun PreviewPicture(
    ch: Channel,
    userAgent: String,
    onOpen: () -> Unit,
    modifier: Modifier,
    onPlayer: ((Player?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val repo = remember { (context.applicationContext as FalakApp).repo }
    var status by remember { mutableStateOf(PreviewStatus.LOADING) }
    // A small buffer: it is only a preview, and the full player may start right after it.
    val exo = remember {
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 10_000, 800, 1_500)
            .setTargetBufferBytes(10 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context, renderers).setLoadControl(load).build()
    }
    DisposableEffect(exo) {
        repo.previewOpen = true
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) status = PreviewStatus.PLAYING
            }

            override fun onPlayerError(error: PlaybackException) {
                status = PreviewStatus.FAILED
            }
        }
        exo.addListener(listener)
        onPlayer?.invoke(exo)
        onDispose {
            onPlayer?.invoke(null)
            exo.removeListener(listener)
            exo.release()
            repo.previewOpen = false
        }
    }
    // Sent to the background (Home pressed, full player opened): stop the sound and the download.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, exo) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exo.stop()
                Lifecycle.Event.ON_START -> if (exo.mediaItemCount > 0) {
                    exo.prepare()
                    exo.playWhenReady = true
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(ch.id, ch.url) {
        status = PreviewStatus.LOADING
        exo.stop()
        exo.clearMediaItems()
        val settings = repo.state.value.settings
        val url = if (settings.preferHls && ch.url.contains("/live/") && ch.url.endsWith(".ts")) {
            ch.url.removeSuffix(".ts") + ".m3u8"
        } else {
            ch.url
        }
        val http = OkHttpDataSource.Factory(repo.http).setUserAgent(userAgent.ifBlank { Repository.DEFAULT_UA })
        if (ch.referrer.isNotBlank()) http.setDefaultRequestProperties(mapOf("Referer" to ch.referrer))
        val data = DefaultDataSource.Factory(context, http)
        val item = MediaItem.Builder().setUri(url).apply {
            when {
                url.contains("m3u8", ignoreCase = true) -> setMimeType(MimeTypes.APPLICATION_M3U8)
                url.contains(".mpd", ignoreCase = true) -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        val source = if (url.contains("m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(data).createMediaSource(item)
        } else {
            val extractors = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            DefaultMediaSourceFactory(data, extractors).createMediaSource(item)
        }
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = exo
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        when (status) {
            PreviewStatus.LOADING -> CircularProgressIndicator(color = Signal, strokeWidth = 2.dp)
            PreviewStatus.FAILED -> Text(
                "No preview\nOpen it full screen",
                fontFamily = Sans,
                fontSize = 12.sp,
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
            PreviewStatus.PLAYING -> Unit
        }
        // A tap on the picture opens it full screen (not a focus stop for the remote).
        Box(Modifier.fillMaxSize().pointerInput(onOpen) { detectTapGestures { onOpen() } })
    }
}
