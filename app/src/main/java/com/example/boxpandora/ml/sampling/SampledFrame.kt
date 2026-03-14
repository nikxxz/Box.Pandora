package com.example.boxpandora.ml.sampling

import android.graphics.Bitmap

/**
 * A single frame extracted from a GIF or video, ready for ML inference.
 *
 * @param bitmap      Downscaled frame bitmap. Caller is responsible for recycling after use.
 * @param scanMethod  Descriptor of how the frame was sampled (e.g. "gif_first_frame",
 *                    "uniform_4_frame_2"). Stored in the [ImageEmbedding.scanMethod] column.
 */
data class SampledFrame(
    val bitmap: Bitmap,
    val scanMethod: String
)
