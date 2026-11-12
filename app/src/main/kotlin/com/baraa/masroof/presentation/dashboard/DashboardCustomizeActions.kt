package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.dashboard.DashboardLayoutPreferencesRepository
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import com.baraa.masroof.application.dashboard.DashboardSectionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The customize sheet of [DashboardViewModel]. It shares the dashboard state, so screens keep one state object. */
internal class DashboardCustomizeActions(
    private val state: MutableStateFlow<DashboardUiState>,
    private val layoutPreferencesRepository: DashboardLayoutPreferencesRepository,
) {
    fun openCustomizeSheet() {
        state.update {
            it.copy(
                customizeSheetOpen = true,
                customizeDraft = it.dashboardLayout,
            )
        }
    }

    fun dismissCustomizeSheet() {
        state.update {
            it.copy(
                customizeSheetOpen = false,
                customizeDraft = null,
            )
        }
    }

    fun saveCustomizeLayout() {
        val draft = state.value.customizeDraft ?: return
        layoutPreferencesRepository.save(draft)
        state.update {
            it.copy(
                dashboardLayout = draft,
                customizeSheetOpen = false,
                customizeDraft = null,
            )
        }
    }

    fun toggleCustomizeSection(id: DashboardSectionId) {
        mutateCustomizeDraft { snapshot ->
            snapshot.copy(
                sections = snapshot.sections.map { entry ->
                    if (entry.id == id) entry.copy(visible = !entry.visible) else entry
                },
            )
        }
    }

    fun moveCustomizeSection(id: DashboardSectionId, direction: Int) {
        mutateCustomizeDraft { snapshot ->
            val sections = snapshot.sections.toMutableList()
            val index = sections.indexOfFirst { it.id == id }
            if (index < 0) return@mutateCustomizeDraft snapshot
            // Step past sections the sheet does not show, so Up/Down swaps with a visible row.
            var target = index + direction
            while (target in sections.indices && sections[target].id in CUSTOMIZE_SHEET_HIDDEN_SECTIONS) target += direction
            if (target !in sections.indices) return@mutateCustomizeDraft snapshot
            val item = sections.removeAt(index)
            sections.add(target, item)
            snapshot.copy(sections = sections)
        }
    }

    fun toggleCustomizeQuickExpense() {
        mutateCustomizeDraft { snapshot ->
            snapshot.copy(quickExpenseVisible = !snapshot.quickExpenseVisible)
        }
    }

    fun toggleCustomizeQuickIncome() {
        mutateCustomizeDraft { snapshot ->
            snapshot.copy(quickIncomeVisible = !snapshot.quickIncomeVisible)
        }
    }

    private fun mutateCustomizeDraft(transform: (DashboardLayoutSnapshot) -> DashboardLayoutSnapshot) {
        state.update { current ->
            val base = current.customizeDraft ?: current.dashboardLayout
            current.copy(customizeDraft = transform(base))
        }
    }
}
