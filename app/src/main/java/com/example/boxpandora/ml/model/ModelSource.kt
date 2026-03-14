package com.example.boxpandora.ml.model

import java.io.File

/**
 * Where to obtain the model binary.
 *
 * BundledAsset  – already included in the APK assets/ directory.
 * RemoteDownload – must be fetched from a URL (Wi-Fi-only policy enforced by ModelManager).
 * LocalFile     – already present on device storage (e.g. sideloaded or pre-cached);
 *                 the caller owns the [file] and is responsible for its deletion after install.
 */
sealed class ModelSource {
    /** Model ships with the APK under [assetPath], e.g. "models/scene/mobilenet_v3_scene.tflite". */
    data class BundledAsset(val assetPath: String) : ModelSource()

    /** Model must be downloaded from [url] before installation. */
    data class RemoteDownload(val url: String) : ModelSource()

    /** Model binary is already present at [file] on local storage. */
    data class LocalFile(val file: File) : ModelSource()
}
