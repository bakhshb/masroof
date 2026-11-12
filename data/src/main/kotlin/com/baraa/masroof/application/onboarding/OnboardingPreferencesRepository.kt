package com.baraa.masroof.application.onboarding

@kotlinx.serialization.Serializable
data class OnboardingSetupDraft(
    val bankIds: Set<String>,
    val from: String,
    val through: String,
    val banksConfirmed: Boolean = false,
)

/**
 * Small persisted onboarding setup state.
 *
 * Runtime permission truth still comes from Android checks.
 */
interface OnboardingPreferencesRepository {
    fun isOnboardingStarted(): Boolean

    fun setOnboardingStarted(started: Boolean)

    fun isOnboardingCompleted(): Boolean

    fun setOnboardingCompleted(completed: Boolean)

    fun getHistoricalImportStartEpochMillis(): Long?

    fun setHistoricalImportStartEpochMillis(epochMillis: Long?)

    fun isHistoricalImportCompleted(): Boolean

    fun setHistoricalImportCompleted(completed: Boolean)

    fun getSetupDraft(): OnboardingSetupDraft?

    fun setSetupDraft(draft: OnboardingSetupDraft?)

    /** First-run home guide. Not part of backups, so a restored install sees it once more. */
    fun isHomeGuideSeen(): Boolean = true

    fun setHomeGuideSeen(seen: Boolean) {}
}
