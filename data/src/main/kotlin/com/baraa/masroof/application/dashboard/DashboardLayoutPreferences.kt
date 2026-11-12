package com.baraa.masroof.application.dashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface DashboardLayoutPreferencesRepository {
    fun load(): DashboardLayoutSnapshot
    fun save(snapshot: DashboardLayoutSnapshot)
}

@Serializable
enum class DashboardSectionId {
    @SerialName("hero")
    HERO,

    @SerialName("quick")
    QUICK,

    @SerialName("accounts")
    ACCOUNTS,

    @SerialName("cards")
    CARDS,

    @SerialName("loans")
    LOANS,

    @SerialName("commitments")
    COMMITMENTS,

    @SerialName("merchants")
    MERCHANTS,

    @SerialName("transactions")
    TRANSACTIONS,
}

@Serializable
enum class DashboardSectionSize {
    @SerialName("sm")
    SMALL,

    @SerialName("md")
    MEDIUM,

    @SerialName("lg")
    LARGE,
}

@Serializable
data class DashboardSectionEntry(
    val id: DashboardSectionId,
    val visible: Boolean = true,
    val size: DashboardSectionSize = DashboardSectionSize.MEDIUM,
)

@Serializable
data class DashboardLayoutSnapshot(
    val sections: List<DashboardSectionEntry>,
    val quickExpenseVisible: Boolean = true,
    val quickIncomeVisible: Boolean = true,
) {
    fun orderedVisibleSections(): List<DashboardSectionEntry> =
        sections.filter { it.visible }

    fun entry(id: DashboardSectionId): DashboardSectionEntry? =
        sections.firstOrNull { it.id == id }

    /** Appends sections introduced after a saved layout was stored (e.g. LOANS). */
    fun withMergedSections(): DashboardLayoutSnapshot {
        val defaults = default()
        val existingIds = sections.map { it.id }.toSet()
        val legacyOrder = listOf(DashboardSectionId.HERO, DashboardSectionId.QUICK,
            DashboardSectionId.ACCOUNTS, DashboardSectionId.CARDS, DashboardSectionId.LOANS,
            DashboardSectionId.COMMITMENTS, DashboardSectionId.MERCHANTS, DashboardSectionId.TRANSACTIONS)
        if (sections.map { it.id } == legacyOrder.filter { it in existingIds }) {
            val stored = sections.associateBy { it.id }
            return copy(sections = defaults.sections.map { stored[it.id] ?: it })
        }
        val missing = defaults.sections.filter { it.id !in existingIds }
        if (missing.isEmpty()) return this

        val merged = sections.toMutableList()
        val knownIds = existingIds.toMutableSet()
        missing.forEach { newEntry ->
            val defaultIndex = defaults.sections.indexOfFirst { it.id == newEntry.id }
            val anchorAfter = defaults.sections
                .drop(defaultIndex + 1)
                .firstOrNull { it.id in knownIds }
            val insertAt = when {
                anchorAfter != null -> merged.indexOfFirst { it.id == anchorAfter.id }
                else -> {
                    val anchorBefore = defaults.sections
                        .take(defaultIndex)
                        .lastOrNull { it.id in knownIds }
                    if (anchorBefore != null) {
                        merged.indexOfFirst { it.id == anchorBefore.id } + 1
                    } else {
                        merged.size
                    }
                }
            }
            merged.add(insertAt.coerceIn(0, merged.size), newEntry)
            knownIds += newEntry.id
        }
        return copy(sections = merged)
    }

    companion object {
        fun default(): DashboardLayoutSnapshot =
            DashboardLayoutSnapshot(
                sections = listOf(
                    DashboardSectionEntry(DashboardSectionId.HERO, size = DashboardSectionSize.LARGE),
                    DashboardSectionEntry(DashboardSectionId.ACCOUNTS, size = DashboardSectionSize.MEDIUM),
                    DashboardSectionEntry(DashboardSectionId.COMMITMENTS, size = DashboardSectionSize.MEDIUM),
                    DashboardSectionEntry(DashboardSectionId.QUICK, size = DashboardSectionSize.MEDIUM),
                    DashboardSectionEntry(DashboardSectionId.CARDS, size = DashboardSectionSize.LARGE),
                    DashboardSectionEntry(DashboardSectionId.LOANS, size = DashboardSectionSize.MEDIUM),
                    DashboardSectionEntry(DashboardSectionId.MERCHANTS, size = DashboardSectionSize.MEDIUM),
                    DashboardSectionEntry(DashboardSectionId.TRANSACTIONS, size = DashboardSectionSize.MEDIUM),
                ),
            )
    }
}
