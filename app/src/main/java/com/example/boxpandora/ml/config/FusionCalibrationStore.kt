package com.example.boxpandora.ml.config

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "pandora_fusion_calibration"
private const val KEY_VERSION                  = "version"
private const val KEY_AMBIGUITY_MARGIN_MULT    = "ambiguity_margin_mult"
private const val KEY_AGREEMENT_BONUS_SCALE    = "agreement_bonus_scale"
private const val KEY_RELIABILITY_WEIGHT_STR   = "reliability_weight_strength"
private const val KEY_IDENTITY_THRESHOLD_MULT  = "identity_threshold_mult"

private fun keyCategoryMult(category: String) = "cat_mult_$category"

/**
 * Persists the current [FusionCalibration] snapshot in SharedPreferences.
 *
 * Ships with [FusionCalibration.DEFAULT] values — calling [save] with a modified snapshot
 * should always include an updated [FusionCalibration.version] so that run manifests remain
 * correlated with the correct calibration state.
 *
 * Categories with no persisted override return a multiplier of 1.0 (no change from defaults).
 */
class FusionCalibrationStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the current calibration, using [FusionCalibration.DEFAULT] for any unset field. */
    fun load(): FusionCalibration {
        val categoryMults = prefs.all
            .filterKeys { it.startsWith("cat_mult_") }
            .mapKeys { it.key.removePrefix("cat_mult_") }
            .mapValues { (it.value as? Float) ?: 1.0f }

        return FusionCalibration(
            version                     = prefs.getString(KEY_VERSION, "default") ?: "default",
            categoryThresholdMultipliers = categoryMults,
            ambiguityMarginMultiplier   = prefs.getFloat(KEY_AMBIGUITY_MARGIN_MULT, 1.0f),
            agreementBonusScale         = prefs.getFloat(KEY_AGREEMENT_BONUS_SCALE, 1.0f),
            reliabilityWeightStrength   = prefs.getFloat(KEY_RELIABILITY_WEIGHT_STR, 1.0f),
            identityThresholdMultiplier = prefs.getFloat(KEY_IDENTITY_THRESHOLD_MULT, 1.0f),
        )
    }

    /** Persists all fields in [cal]. */
    fun save(cal: FusionCalibration) {
        val edit = prefs.edit()
            .putString(KEY_VERSION, cal.version)
            .putFloat(KEY_AMBIGUITY_MARGIN_MULT, cal.ambiguityMarginMultiplier)
            .putFloat(KEY_AGREEMENT_BONUS_SCALE, cal.agreementBonusScale)
            .putFloat(KEY_RELIABILITY_WEIGHT_STR, cal.reliabilityWeightStrength)
            .putFloat(KEY_IDENTITY_THRESHOLD_MULT, cal.identityThresholdMultiplier)
        cal.categoryThresholdMultipliers.forEach { (cat, mult) ->
            edit.putFloat(keyCategoryMult(cat), mult)
        }
        edit.apply()
    }

    /** Resets every field to [FusionCalibration.DEFAULT]. */
    fun reset() {
        prefs.edit().clear().apply()
    }
}
