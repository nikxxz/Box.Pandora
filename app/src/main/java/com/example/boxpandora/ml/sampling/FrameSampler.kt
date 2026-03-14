package com.example.boxpandora.ml.sampling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

private const val TAG = "FrameSampler"

/**
 * Produces a small set of representative [SampledFrame]s from a GIF or video URI
 * for scene embedding inference.
 *
 * All returned bitmaps are downscaled so the long edge does not exceed
 * [MAX_FRAME_DIMENSION] pixels before inference. Callers must recycle each
 * [SampledFrame.bitmap] after the embedding is computed.
 *
 * **GIF frame sampling** (Phase 6): decodes the first frame via [BitmapFactory].
 * Android does not expose multi-frame GIF iteration without a third-party library;
 * full N-frame support is a future upgrade.
 *
 * **Video frame sampling**: uses [MediaMetadataRetriever] to extract up to
 * [MAX_VIDEO_FRAMES] frames at uniform time intervals across the full duration.
 */
object FrameSampler {

    /** Maximum number of frames extracted from a GIF. */
    const val MAX_GIF_FRAMES: Int = 1        // single representative frame (Phase 6 baseline)

    /** Maximum number of frames extracted from a video. */
    const val MAX_VIDEO_FRAMES: Int = 4

    /** Long-edge cap (px) applied to every sampled frame before inference. */
    const val MAX_FRAME_DIMENSION: Int = 480

    // ── GIF ───────────────────────────────────────────────────────────────────

    /**
     * Returns up to [MAX_GIF_FRAMES] representative frames from the GIF at [uri].
     *
     * Phase 6 baseline: always returns the first decoded frame.
     * Returns an empty list if the URI cannot be opened or decoded.
     */
    fun sampleGifFrames(context: Context, uri: Uri): List<SampledFrame> {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        if (bitmap == null) {
            Log.w(TAG, "sampleGifFrames: could not decode $uri")
            return emptyList()
        }
        val scaled = downscaleIfNeeded(bitmap)
        if (scaled !== bitmap) bitmap.recycle()
        return listOf(SampledFrame(scaled, "gif_first_frame"))
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    /**
     * Returns up to [MAX_VIDEO_FRAMES] frames sampled at uniform intervals from [uri].
     *
     * Uses [MediaMetadataRetriever.OPTION_CLOSEST_SYNC] so each seek lands on an
     * actual keyframe, keeping extraction fast.
     *
     * Returns an empty list if the video cannot be opened, has zero duration,
     * or all frame extractions fail.
     */
    fun sampleVideoFrames(context: Context, uri: Uri): List<SampledFrame> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: run {
                    Log.w(TAG, "sampleVideoFrames: no duration for $uri")
                    return emptyList()
                }
            if (durationMs <= 0L) return emptyList()

            val frameCount = MAX_VIDEO_FRAMES
            val frames = mutableListOf<SampledFrame>()

            for (i in 0 until frameCount) {
                // Spread evenly across duration; start slightly past 0 to avoid black frames
                val timeMs = durationMs * (i * 2 + 1) / (frameCount * 2)
                val raw = retriever.getFrameAtTime(
                    timeMs * 1_000L,   // microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue
                val scaled = downscaleIfNeeded(raw)
                if (scaled !== raw) raw.recycle()
                frames.add(SampledFrame(scaled, "uniform_${frameCount}_frame_$i"))
            }

            Log.d(TAG, "sampleVideoFrames: extracted ${frames.size}/$frameCount frames from $uri")
            frames
        } catch (e: Exception) {
            Log.w(TAG, "sampleVideoFrames: failed for $uri — ${e.message}")
            emptyList()
        } finally {
            retriever.release()
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /**
     * Returns [bitmap] unchanged if its long edge is already ≤ [MAX_FRAME_DIMENSION];
     * otherwise returns a downscaled copy with the same aspect ratio.
     * Does NOT recycle the input.
     */
    fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= MAX_FRAME_DIMENSION) return bitmap
        val scale = MAX_FRAME_DIMENSION.toFloat() / maxDim
        val newW = (bitmap.width  * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
