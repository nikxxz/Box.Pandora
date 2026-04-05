package com.example.boxpandora.ml.postprocessing

/**
 * Central configuration for tag normalization, suppression, and promotion rules.
 *
 * All dictionaries are defined here so that tuning tag quality never requires touching
 * business logic in [TagSuggestionPostProcessor] or UI code.
 */
object TagNormalizationConfig {

    /**
     * Maps synonym or informal label → canonical tag key.
     *
     * Used to collapse duplicate groups: if both the canonical form and an alias appear
     * in the raw suggestions, the canonical form is kept and the alias is discarded.
     * If only the alias appears, it is kept as-is to avoid breaking DB row references.
     */
    val synonymToCanonical: Map<String, String> = mapOf(
        // ── Animals ──────────────────────────────────────────────────────────────
        "canine"    to "dog",
        "doggy"     to "dog",
        "doggie"    to "dog",
        "puppy"     to "dog",
        "pup"       to "dog",
        "feline"    to "cat",
        "kitty"     to "cat",
        "kitten"    to "cat",
        "pony"      to "horse",
        "foal"      to "horse",

        // ── Nature / Scene ────────────────────────────────────────────────────────
        "woods"     to "forest",
        "woodland"  to "forest",
        "jungle"    to "forest",
        "sea"       to "ocean",
        "pond"      to "lake",
        "stream"    to "river",
        "creek"     to "river",
        "meadow"    to "field",

        // ── Location descriptors ──────────────────────────────────────────────────
        "outdoors"  to "outdoor",
        "outside"   to "outdoor",
        "exterior"  to "outdoor",
        "indoors"   to "indoor",
        "interior"  to "indoor",

        // ── Vehicles ──────────────────────────────────────────────────────────────
        "automobile" to "car",
        "auto"       to "car",

        // ── People ────────────────────────────────────────────────────────────────
        "lady"       to "woman",
        "female"     to "woman",
        "gentleman"  to "man",
        "male"       to "man",
        "infant"     to "baby",
        "toddler"    to "child",
    )

    /**
     * Parent → set of children that make the parent redundant.
     *
     * When any child tag is present in the suggestion set, the parent tag is suppressed.
     * Example: "pet" is suppressed when "dog" or "cat" is already present.
     */
    val parentToChildren: Map<String, Set<String>> = mapOf(
        "pet"     to setOf("dog", "cat", "bird", "rabbit", "fish", "hamster"),
        "animal"  to setOf(
            "dog", "cat", "bird", "horse", "rabbit", "fish", "cow", "sheep",
            "deer", "bear", "wolf", "fox", "elephant", "lion", "tiger",
        ),
        "person"  to setOf("woman", "man", "child", "girl", "boy", "baby", "adult"),
        "vehicle" to setOf("car", "truck", "motorcycle", "bicycle", "bus", "van"),
        "nature"  to setOf("forest", "beach", "mountain", "lake", "river", "ocean", "field", "desert"),
        "plant"   to setOf("tree", "flower", "grass"),
    )

    /**
     * Tags that are always suppressed from final suggestions because they represent
     * low-level visual fragments rather than meaningful gallery concepts.
     */
    val suppressionSet: Set<String> = setOf(
        // Body fragments
        "hand", "hands", "finger", "fingers", "thumb", "thumbs",
        "arm", "arms", "leg", "legs", "foot", "feet",
        // Textures / surfaces
        "skin", "flesh",
        "fur",
        "texture", "pattern",
        "fabric", "material", "cloth",
        // Photography artifacts
        "shadow", "shadows",
        "background", "foreground",
        "close-up", "closeup", "macro",
        "blur", "bokeh",
        "surface",
        // Generic visual noise
        "light", "lighting",
        "color", "colour",
    )

    /**
     * Canonical bucket assignment for known tag keys.
     *
     * Tags absent from this map default to [TagBucket.GENERIC].
     */
    val tagToBucket: Map<String, TagBucket> = mapOf(
        // ── PRIMARY_SUBJECT: people ───────────────────────────────────────────────
        "woman"   to TagBucket.PRIMARY_SUBJECT,
        "man"     to TagBucket.PRIMARY_SUBJECT,
        "person"  to TagBucket.PRIMARY_SUBJECT,
        "child"   to TagBucket.PRIMARY_SUBJECT,
        "girl"    to TagBucket.PRIMARY_SUBJECT,
        "boy"     to TagBucket.PRIMARY_SUBJECT,
        "baby"    to TagBucket.PRIMARY_SUBJECT,
        "adult"   to TagBucket.PRIMARY_SUBJECT,
        "couple"  to TagBucket.PRIMARY_SUBJECT,
        "group"   to TagBucket.PRIMARY_SUBJECT,

        // ── PRIMARY_SUBJECT: common animals ───────────────────────────────────────
        "dog"       to TagBucket.PRIMARY_SUBJECT,
        "cat"       to TagBucket.PRIMARY_SUBJECT,
        "bird"      to TagBucket.PRIMARY_SUBJECT,
        "horse"     to TagBucket.PRIMARY_SUBJECT,
        "rabbit"    to TagBucket.PRIMARY_SUBJECT,
        "fish"      to TagBucket.PRIMARY_SUBJECT,
        "cow"       to TagBucket.PRIMARY_SUBJECT,
        "sheep"     to TagBucket.PRIMARY_SUBJECT,
        "deer"      to TagBucket.PRIMARY_SUBJECT,
        "bear"      to TagBucket.PRIMARY_SUBJECT,
        "wolf"      to TagBucket.PRIMARY_SUBJECT,
        "fox"       to TagBucket.PRIMARY_SUBJECT,
        "elephant"  to TagBucket.PRIMARY_SUBJECT,
        "lion"      to TagBucket.PRIMARY_SUBJECT,
        "tiger"     to TagBucket.PRIMARY_SUBJECT,

        // ── PRIMARY_SUBJECT: key objects ──────────────────────────────────────────
        "car"         to TagBucket.PRIMARY_SUBJECT,
        "truck"       to TagBucket.PRIMARY_SUBJECT,
        "bicycle"     to TagBucket.PRIMARY_SUBJECT,
        "motorcycle"  to TagBucket.PRIMARY_SUBJECT,
        "boat"        to TagBucket.PRIMARY_SUBJECT,
        "airplane"    to TagBucket.PRIMARY_SUBJECT,
        "flower"      to TagBucket.PRIMARY_SUBJECT,

        // ── SCENE: outdoor nature ─────────────────────────────────────────────────
        "forest"   to TagBucket.SCENE,
        "beach"    to TagBucket.SCENE,
        "mountain" to TagBucket.SCENE,
        "lake"     to TagBucket.SCENE,
        "river"    to TagBucket.SCENE,
        "ocean"    to TagBucket.SCENE,
        "field"    to TagBucket.SCENE,
        "desert"   to TagBucket.SCENE,
        "garden"   to TagBucket.SCENE,
        "park"     to TagBucket.SCENE,
        "outdoor"  to TagBucket.SCENE,

        // ── SCENE: indoor / urban ─────────────────────────────────────────────────
        "indoor"        to TagBucket.SCENE,
        "kitchen"       to TagBucket.SCENE,
        "bedroom"       to TagBucket.SCENE,
        "living room"   to TagBucket.SCENE,
        "bathroom"      to TagBucket.SCENE,
        "office"        to TagBucket.SCENE,
        "street"        to TagBucket.SCENE,
        "city"          to TagBucket.SCENE,
        "road"          to TagBucket.SCENE,

        // ── SCENE: sky / weather ──────────────────────────────────────────────────
        "sky"     to TagBucket.SCENE,
        "sunset"  to TagBucket.SCENE,
        "sunrise" to TagBucket.SCENE,
        "night"   to TagBucket.SCENE,
        "snow"    to TagBucket.SCENE,
        "rain"    to TagBucket.SCENE,
        "fog"     to TagBucket.SCENE,

        // ── ACTION ────────────────────────────────────────────────────────────────
        "walking"  to TagBucket.ACTION,
        "running"  to TagBucket.ACTION,
        "sitting"  to TagBucket.ACTION,
        "standing" to TagBucket.ACTION,
        "eating"   to TagBucket.ACTION,
        "drinking" to TagBucket.ACTION,
        "playing"  to TagBucket.ACTION,
        "cooking"  to TagBucket.ACTION,
        "swimming" to TagBucket.ACTION,
        "hiking"   to TagBucket.ACTION,
        "cycling"  to TagBucket.ACTION,
        "dancing"  to TagBucket.ACTION,
        "reading"  to TagBucket.ACTION,
        "smiling"  to TagBucket.ACTION,
        "laughing" to TagBucket.ACTION,
        "sleeping" to TagBucket.ACTION,
        "driving"  to TagBucket.ACTION,
        "jumping"  to TagBucket.ACTION,
        "hugging"  to TagBucket.ACTION,
        "posing"   to TagBucket.ACTION,

        // ── GENERIC: broad category labels ───────────────────────────────────────
        "pet"      to TagBucket.GENERIC,
        "animal"   to TagBucket.GENERIC,
        "plant"    to TagBucket.GENERIC,
        "nature"   to TagBucket.GENERIC,
        "vehicle"  to TagBucket.GENERIC,
        "food"     to TagBucket.GENERIC,
        "water"    to TagBucket.GENERIC,
        "tree"     to TagBucket.GENERIC,
        "grass"    to TagBucket.GENERIC,

        // ── LOW_VALUE_DETAIL ──────────────────────────────────────────────────────
        "hand"      to TagBucket.LOW_VALUE_DETAIL,
        "hands"     to TagBucket.LOW_VALUE_DETAIL,
        "finger"    to TagBucket.LOW_VALUE_DETAIL,
        "fingers"   to TagBucket.LOW_VALUE_DETAIL,
        "skin"      to TagBucket.LOW_VALUE_DETAIL,
        "fur"       to TagBucket.LOW_VALUE_DETAIL,
        "texture"   to TagBucket.LOW_VALUE_DETAIL,
        "pattern"   to TagBucket.LOW_VALUE_DETAIL,
        "shadow"    to TagBucket.LOW_VALUE_DETAIL,
        "arm"       to TagBucket.LOW_VALUE_DETAIL,
        "leg"       to TagBucket.LOW_VALUE_DETAIL,
        "leaf"      to TagBucket.LOW_VALUE_DETAIL,
        "fabric"    to TagBucket.LOW_VALUE_DETAIL,
    )

    /**
     * Conservative multi-tag promotion rules.
     *
     * When all [PromotionRule.triggerTags] are present with at least [PromotionRule.minTriggerScore]
     * confidence and the promoted tag does not already exist, a new suggestion is synthesised.
     */
    val promotionRules: List<PromotionRule> = listOf(
        // Dog-walking context
        PromotionRule(
            triggerTags     = setOf("dog", "person"),
            promotedTag     = "walking",
            minTriggerScore = 0.55,
            promotedScore   = 0.62,
        ),
        // Forest / woodland context from generic nature cues
        PromotionRule(
            triggerTags     = setOf("tree", "outdoor"),
            promotedTag     = "forest",
            minTriggerScore = 0.55,
            promotedScore   = 0.60,
        ),
        // Beach context
        PromotionRule(
            triggerTags     = setOf("ocean", "outdoor"),
            promotedTag     = "beach",
            minTriggerScore = 0.55,
            promotedScore   = 0.60,
        ),
        // Hiking context
        PromotionRule(
            triggerTags     = setOf("mountain", "person", "outdoor"),
            promotedTag     = "hiking",
            minTriggerScore = 0.55,
            promotedScore   = 0.60,
        ),
        // Outdoor meal / picnic
        PromotionRule(
            triggerTags     = setOf("food", "outdoor"),
            promotedTag     = "picnic",
            minTriggerScore = 0.55,
            promotedScore   = 0.58,
        ),
    )
}

/**
 * A rule that synthesises a promoted tag suggestion when all [triggerTags] meet the
 * [minTriggerScore] threshold and the promoted tag is not already present.
 */
data class PromotionRule(
    val triggerTags: Set<String>,
    val promotedTag: String,
    /** Minimum score that each trigger tag must have to fire this rule. */
    val minTriggerScore: Double,
    /** Score assigned to the synthesised promoted suggestion. */
    val promotedScore: Double,
)
