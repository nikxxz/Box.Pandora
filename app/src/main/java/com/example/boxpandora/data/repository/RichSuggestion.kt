package com.example.boxpandora.data.repository

import com.example.boxpandora.ml.ensemble.AgreementLevel

/**
 * Domain-level flat representation of a tag suggestion returned by the repository layer.
 *
 * Unifies single-model [TagSuggestion][com.example.boxpandora.data.local.entity.TagSuggestion],
 * heuristic, and ensemble-fused
 * [FusedSceneSuggestion][com.example.boxpandora.data.local.entity.FusedSceneSuggestion] rows
 * behind a single stable caller API so that ViewModels and UI never need to inspect source
 * strings or know which backing pipeline produced the suggestion.
 *
 * **Fused-only fields** ([contributingModelCount], [agreementLevel], [isAmbiguous],
 * [tagCategory], [isFused]) carry meaningful values only when [isFused] is true; for
 * non-fused rows they default to 0 / null / false / "misc" / false respectively.
 *
 * ### Source values
 * | [source] value      | Origin                                     |
 * |:-------------------:|:------------------------------------------:|
 * | `"generic_vocab"`   | Single-model vocabulary suggestion         |
 * | `"prototype"`       | Learned centroid prototype match           |
 * | `"cooccurrence"`    | Tag co-occurrence rule                     |
 * | `"heuristic"`       | Rule-based heuristic tagger                |
 * | `"fused_scene"`     | Ensemble-fused scene suggestion            |
 */
data class RichSuggestion(
    /** Row ID from the backing table (0 for heuristic rows with a composite primary key). */
    val id: Long,
    val assetId: String,
    val tagKey: String,

    /** Confidence in the range 0.0–1.0. For fused rows this is the reliability-weighted score. */
    val score: Double,

    /**
     * Source pipeline identifier. Safe for UI source-badge lookups; do not parse for
     * fused-specific metadata — use the structured fields below instead.
     */
    val source: String,

    /** Model manifest version key or ensemble pipeline run ID for provenance tracing. */
    val modelVersion: String = "0",

    // ── Fused-only fields ─────────────────────────────────────────────────────

    /** Number of models that contributed to the fused score; 0 when [isFused] is false. */
    val contributingModelCount: Int = 0,

    /**
     * Pre-computed agreement level from fusion time. Null when [isFused] is false.
     * Use [com.example.boxpandora.ml.ensemble.toLabel] with [contributingModelCount] for
     * the compact badge string shown in the UI.
     */
    val agreementLevel: AgreementLevel? = null,

    /** True when the fused score barely leads the second-best tag in the same category. */
    val isAmbiguous: Boolean = false,

    /**
     * Content category resolved at fusion time ("animal", "mood", etc.).
     * Defaults to "misc" for non-fused rows.
     */
    val tagCategory: String = "misc",

    /** True when this row was produced by the ensemble fusion pipeline. */
    val isFused: Boolean = false,
)
