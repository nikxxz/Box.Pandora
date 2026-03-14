package com.example.boxpandora.ml.sampling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
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
 * **GIF frame sampling**: uses [android.graphics.Movie] (deprecated API 31, still functional)
 * to render up to [MAX_GIF_FRAMES] frames at evenly-spread time positions — always including
 * the first and last frames. Falls back to [BitmapFactory] (first frame only) when Movie
 * cannot open the file or reports zero duration.
 *
 * **Video frame sampling**: uses [MediaMetadataRetriever] to extract up to
 * [MAX_VIDEO_FRAMES] frames at uniform time intervals across the full duration.
 */
object FrameSampler {

    /**
     * Maximum number of frames sampled from a GIF.
     * Sparse sampling always covers first + last; middle frames fill the remaining slots.
     */
    const val MAX_GIF_FRAMES: Int = 4

    /** Maximum number of frames extracted from a video. */
    const val MAX_VIDEO_FRAMES: Int = 10

    /** Long-edge cap (px) applied to every sampled frame before inference. */
    const val MAX_FRAME_DIMENSION: Int = 480

    // ── GIF ───────────────────────────────────────────────────────────────────

    /**
     * Returns up to [MAX_GIF_FRAMES] representative frames from the GIF at [uri].
     *
     * Sparse sampling strategy: frames are taken at evenly-distributed time positions
     * across the full animation duration so that the first, last, and intermediate states
     * are all represented. If the GIF has very few distinct frames (estimated from duration),
     * fewer samples are taken to avoid redundant renders.
     *
     * Falls back to a single first frame if [Movie] cannot open the file.
     *
     * **Face detection**: GIF face detection remains off regardless of this function.
     * This sampler is used only by the scene-embedding pipeline.
     */
    fun sampleGifFrames(context: Context, uri: Uri): List<SampledFrame> {
        // Attempt multi-frame extraction via Movie. Movie is deprecated in API 31 but
        // remains the only built-in way to render individual GIF frames without a
        // third-party library, and continues to function on all current Android versions.
        @Suppress("DEPRECATION")
        val movie: Movie? = context.contentResolver.openInputStream(uri)?.use {
            Movie.decodeStream(it)
        }

        if (movie != null && movie.width() > 0 && movie.height() > 0 && movie.duration() > 0) {
            val frames = sampleMovieFrames(movie)
            if (frames.isNotEmpty()) return frames
        }

        // Fallback: BitmapFactory returns the first frame of an animated GIF.
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

    /**
     * Extracts up to [MAX_GIF_FRAMES] sparse frames from [movie].
     *
     * Time positions are spread evenly from 0 to (duration − 1) ms so the first frame
     * (t = 0) and last frame (t = duration − 1) are always included. The number of
     * positions is capped at [MAX_GIF_FRAMES] AND at the estimated number of distinct
     * frames (duration / 100 ms ≈ 10 fps) to avoid rendering the same visual twice.
     *
     * Each frame is rendered into a fresh [Bitmap] via [Canvas], then downscaled if needed.
     */
    @Suppress("DEPRECATION")
    private fun sampleMovieFrames(movie: Movie): List<SampledFrame> {
        val duration  = movie.duration()  // total animation duration in ms
        val w         = movie.width()
        val h         = movie.height()

        // Estimate distinct frame count assuming ~10 fps; avoid over-sampling short GIFs.
        val estimatedFrames = (duration / 100).coerceAtLeast(1)
        val n = minOf(MAX_GIF_FRAMES, estimatedFrames)

        // Build n evenly-spaced time positions in [0, duration-1].
        val times: List<Int> = if (n == 1) {
            listOf(0)
        } else {
            (0 until n).map { i -> ((duration - 1L) * i / (n - 1)).toInt() }
        }

        val paint  = Paint(Paint.FILTER_BITMAP_FLAG)
        val frames = mutableListOf<SampledFrame>()

        for ((idx, t) in times.withIndex()) {
            val bmp    = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            movie.setTime(t)
            movie.draw(canvas, 0f, 0f, paint)

            val scaled = downscaleIfNeeded(bmp)
            if (scaled !== bmp) bmp.recycle()

            // Label: "gif_first_frame" for the first position (backward-compatible with
            // existing single-frame embeddings), positional labels for the rest.
            val label = when {
                idx == 0     -> "gif_first_frame"
                idx == n - 1 -> "gif_last_frame"
                else         -> "gif_mid_frame_$idx"
            }
            frames.add(SampledFrame(scaled, label))
        }

        Log.d(TAG, "sampleMovieFrames: sampled ${frames.size}/$n frames (duration=${duration}ms, ~${estimatedFrames} distinct)")
        return frames
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
