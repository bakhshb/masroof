package com.baraa.masroof.presentation.settings

import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.patterns.PatternBankView
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.common.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
internal fun SettingsImportBankChoices(banks: List<PatternBankView>, selected: Set<String>, enabled: Boolean,
    onToggle: (String) -> Unit) {
    MasroofCard {
        MasroofGuidanceLabel(stringResource(R.string.settings_import_banks),
            stringResource(R.string.settings_import_scope_hint), style = MaterialTheme.typography.titleMedium)
        MasroofSheetChoices(banks.map { it.bankId }, selected, { id ->
            val bank = banks.single { it.bankId == id }
            settingsBankLabel(Bank(id)) + "\n" + bankSupportLabel(bank).first
        }, { if (enabled) onToggle(it) }, { "import-bank-$it" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsImportPeriod(from: LocalDate, through: LocalDate, enabled: Boolean,
    onFromChange: (LocalDate) -> Unit, onThroughChange: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    var choosingDate by rememberSaveable { mutableStateOf<String?>(null) }
    choosingDate?.let { field ->
        val date = if (field == "from") from else through
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        val draft = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        MasroofBottomSheet(stringResource(if (field == "from") R.string.settings_import_from else R.string.settings_import_through),
            onDismiss = { choosingDate = null }, onApply = {
                draft?.let { if (field == "from") onFromChange(it) else onThroughChange(it) }
                choosingDate = null
            }, applyLabel = stringResource(R.string.transaction_list_filter_apply), canApply = enabled && draft != null && !draft.isAfter(today)) {
            DatePicker(picker, modifier = Modifier.testTag("bank-import-date-picker"), showModeToggle = false)
        }
    }
    MasroofCard {
        MasroofGuidanceLabel(stringResource(R.string.settings_import_period),
            stringResource(R.string.settings_import_period_hint), style = MaterialTheme.typography.titleMedium)
        MasroofReferenceNavigationRow(stringResource(R.string.settings_import_from), from.toString(), MasroofIcons.calendar,
            { if (enabled) choosingDate = "from" }, modifier = Modifier.testTag("import-from"))
        MasroofReferenceNavigationRow(stringResource(R.string.settings_import_through), through.toString(), MasroofIcons.calendar,
            { if (enabled) choosingDate = "through" }, divider = false, modifier = Modifier.testTag("import-through"))
        if (from.isAfter(through) || through.isAfter(today)) MasroofText(stringResource(R.string.settings_import_invalid_period), color = MaterialTheme.colorScheme.error)
    }
}
