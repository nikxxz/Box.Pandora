package com.example.boxpandora.ui.components.media

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.boxpandora.R

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    uri: String,
    isPlaying: Boolean,
    isMuted: Boolean,
    onVideoClick: () -> Unit,
    onProgress: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
    volume: Float = 1f,
    seekTo: Long? = null,
    cropToFill: Boolean = false
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(cropToFill) {
        exoPlayer.videoScalingMode = if (cropToFill)
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        else
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
    }

    LaunchedEffect(uri) {
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(isMuted, volume) {
        exoPlayer.volume = if (isMuted) 0f else volume
    }

    LaunchedEffect(seekTo) {
        seekTo?.let {
            exoPlayer.seekTo(it)
        }
    }

    // Progress tracking
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                if (exoPlayer.duration > 0) {
                    onProgress(exoPlayer.currentPosition, exoPlayer.duration)
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Immediate shutdown to prevent audio/video trailing during navigation
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            // We inflate from XML because Media3 doesn't expose surface_type programmatically.
            // TextureView is required so the video layer follows Compose scale/alpha animations.
            val frameLayout = FrameLayout(ctx)
            val view = LayoutInflater.from(ctx).inflate(R.layout.player_view, frameLayout, false) as PlayerView
            view.player = exoPlayer
            view
        },
        update = { view ->
            // RESIZE_MODE_ZOOM mimics "cover" behavior to fill the visible area without black bars.
            view.resizeMode = if (cropToFill)
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else
                AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onVideoClick()
            }
    )
}
