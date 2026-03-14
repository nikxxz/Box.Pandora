package com.example.boxpandora.ml.model

/**
 * The three canonical model categories for Phase 1 / Phase 2.
 *
 * id matches the directory name used under filesDir/ai_models/{id}/
 * and the modelVersion column stored in Room (e.g. "scene_embedding:mobilenet_v3-1.0.0").
 */
enum class ModelCategory(val id: String, val displayName: String) {
    SCENE_EMBEDDING("scene_embedding", "Scene Embedding"),
    FACE_EMBEDDING("face_embedding", "Face Embedding"),
    FACE_DETECTION("face_detection", "Face Detection")
}
