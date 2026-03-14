package com.example.boxpandora.ml.engine

/**
 * Full scoring breakdown for a single (asset, tag) suggestion candidate.
 *
 * [tagKey]            – normalized tag name
 * [baseSimilarity]    – cosine similarity between asset embedding and tag prototype
 * [cooccurrenceBoost] – secondary boost from co-occurrence with high-scoring tags (0 if none)
 * [finalConfidence]   – baseSimilarity + cooccurrenceBoost − marginPenalty, clamped to [0, 1]
 * [source]            – "scene_prototype" or "scene_prototype+cooccurrence"
 * [sampleCount]       – how many training samples the prototype was built from
 * [marginPenalty]     – confidence deducted by the second-best margin rule (0 when rule not triggered)
 */
data class SuggestionScore(
    val tagKey: String,
    val baseSimilarity: Float,
    val cooccurrenceBoost: Float,
    val finalConfidence: Float,
    val source: String,
    val sampleCount: Int,
    val marginPenalty: Float = 0f
)
