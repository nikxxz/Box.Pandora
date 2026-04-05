package com.example.boxpandora.ml.postprocessing

/**
 * Semantic category for a tag suggestion, used to weight and rank suggestions by usefulness.
 *
 * [priorityWeight] is multiplied against the raw model score to compute a quality-aware
 * ranking score. Higher-priority buckets promote semantically meaningful tags while
 * low-value visual fragments are strongly downranked.
 */
enum class TagBucket(val priorityWeight: Double) {
    /** Primary subjects: people, animals, key objects — most useful for gallery search. */
    PRIMARY_SUBJECT(1.0),

    /** Scene or setting context: forest, beach, kitchen, city, sky. */
    SCENE(0.85),

    /** Actions and activities: walking, running, eating, playing. */
    ACTION(0.70),

    /** Generic or umbrella descriptors: animal, vehicle, nature, plant. */
    GENERIC(0.40),

    /** Low-level visual fragments: hand, skin, fur, texture, shadow. */
    LOW_VALUE_DETAIL(0.05),
}
