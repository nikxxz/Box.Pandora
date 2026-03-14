package com.example.boxpandora.ml.model

import java.io.File

/**
 * A model that has been verified and is present in app-private storage.
 *
 * [metadata] – the manifest entry this file corresponds to
 * [file]     – absolute path to the ready-to-load .tflite file
 * [isActive] – true when this is the currently designated model for its category
 */
data class InstalledModel(
    val metadata: ModelMetadata,
    val file: File,
    val isActive: Boolean
)
