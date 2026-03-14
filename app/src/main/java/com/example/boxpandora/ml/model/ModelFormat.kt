package com.example.boxpandora.ml.model

/**
 * Supported model binary formats.
 *
 * [extension] is the canonical file extension used when writing the model to disk.
 *
 * Only [TFLITE] is actively used in Phase 1. [ONNX] is reserved for future
 * inference backends (e.g. ONNX Runtime for Android).
 */
enum class ModelFormat(val extension: String, val mimeType: String) {
    TFLITE("tflite", "application/octet-stream"),
    ONNX("onnx",     "application/octet-stream")
}
