package com.baraa.masroof.presentation.settings

import com.baraa.masroof.ui.theme.MasroofOpacity
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import com.baraa.masroof.presentation.common.MasroofButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofOutlinedButton as OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.common.MasroofTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.R
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.logging.AppLogEntry
import com.baraa.masroof.application.logging.AppLogLevel
import com.baraa.masroof.presentation.common.IconLabelRow
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.MasroofTextStyles
import com.baraa.masroof.ui.theme.MasroofThemeExtras
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SettingsLogsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onRequestExport: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val entries by viewModel.logEntries.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshLogs() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val shareTitle = stringResource(R.string.settings_logs_share)
    SettingsLogsContent(state, entries, onBack, onRequestExport, viewModel::clearLogs, viewModel::clearLogMessage,
        onShare = { com.baraa.masroof.presentation.dashboard.SharePlainText.share(context, viewModel.logExportText(), shareTitle, shareTitle) })
}

@Composable
internal fun SettingsLogsContent(state: SettingsUiState, entries: List<AppLogEntry>, onBack: () -> Unit,
    onRequestExport: () -> Unit, onClearLogs: () -> Unit, onClearMessage: () -> Unit, onShare: (() -> Unit)? = null) {
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = state.logMessage?.let { resolveLogMessage(it) }
    var selectedFilter by rememberSaveable { mutableStateOf(SettingsLogFilter.ALL) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(com.baraa.masroof.application.locale.AppDigits.display(messageText))
            onClearMessage()
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.settings_logs_clear_confirm_title)) },
            text = { Text(stringResource(R.string.settings_logs_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearLogs()
                    },
                ) {
                    Text(
                        stringResource(R.string.settings_logs_clear_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    val locale = remember(state.languageTag) {
        AppLocale.displayLocale(state.languageTag)
    }
    val zoneId = remember { ZoneId.systemDefault() }
    val filteredEntries = remember(entries, selectedFilter) {
        filterLogEntries(entries, selectedFilter)
    }
    val todayLabel = stringResource(R.string.settings_logs_day_today)
    val yesterdayLabel = stringResource(R.string.settings_logs_day_yesterday)
    val timestampLabels = LogTimestampLabels(
        justNow = stringResource(R.string.settings_log_time_just_now),
        minutesAgoFormat = stringResource(R.string.settings_log_time_minutes_ago),
        hoursAgoFormat = stringResource(R.string.settings_log_time_hours_ago),
    )
    val listItems = remember(filteredEntries, state.languageTag, todayLabel, yesterdayLabel) {
        groupLogEntriesByDay(
            entries = filteredEntries,
            zoneId = zoneId,
            todayLabel = todayLabel,
            yesterdayLabel = yesterdayLabel,
            dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", locale),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MasroofSecondaryScaffold(
            title = stringResource(R.string.settings_logs_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.settings_back),
            actions = { com.baraa.masroof.presentation.common.MasroofGuidanceButton(stringResource(R.string.settings_logs_title), stringResource(R.string.settings_logs_hint)) },
        ) { contentModifier ->
            Column(
                modifier = contentModifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(SettingsSpacing.groupGap)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            stringResource(
                                R.string.settings_logs_status,
                                entries.size,
                                LOG_MAX_ENTRIES,
                                LOG_RETENTION_DAYS,
                            ),
                            modifier = Modifier.padding(horizontal = MasroofSpacing.sectionGap, vertical = MasroofSpacing.smallGap),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                LogFilterChips(
                    selected = selectedFilter,
                    onSelected = { selectedFilter = it },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SettingsSpacing.groupGap),
                ) {
                    Button(
                        onClick = onRequestExport,
                        enabled = entries.isNotEmpty() && !state.exportingLogs,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_logs_export))
                    }
                    if (onShare != null) OutlinedButton(
                        onClick = onShare,
                        enabled = entries.isNotEmpty() && !state.exportingLogs,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_logs_share))
                    }
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        enabled = entries.isNotEmpty() && !state.exportingLogs,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.settings_logs_clear))
                    }
                }

                if (state.exportingLogs) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                when {
                    entries.isEmpty() -> LogEmptyState()
                    filteredEntries.isEmpty() -> LogFilteredEmptyState()
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.groupGap),
                        ) {
                            items(
                                items = listItems,
                                key = { item ->
                                    when (item) {
                                        is SettingsLogListItem.DayHeader -> "header-${item.label}"
                                        is SettingsLogListItem.Entry -> "entry-${item.entry.id}"
                                    }
                                },
                            ) { item ->
                                when (item) {
                                    is SettingsLogListItem.DayHeader ->
                                        Text(
                                            item.label,
                                            style = MasroofTextStyles.sectionTitle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = MasroofSpacing.inlineGap, bottom = MasroofSpacing.hairlineGap),
                                        )
                                    is SettingsLogListItem.Entry ->
                                        LogEntryCard(
                                            entry = item.entry,
                                            locale = locale,
                                            zoneId = zoneId,
                                            timestampLabels = timestampLabels,
                                        )
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LogFilterChips(
    selected: SettingsLogFilter,
    onSelected: (SettingsLogFilter) -> Unit,
) {
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap),
        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
        SettingsLogFilter.entries.forEach { filter ->
            com.baraa.masroof.presentation.common.MasroofChoice(logFilterLabel(filter), selected == filter, { onSelected(filter) })
        }
    }
}

@Composable
private fun logFilterLabel(filter: SettingsLogFilter): String =
    when (filter) {
        SettingsLogFilter.ALL -> stringResource(R.string.settings_logs_filter_all)
        SettingsLogFilter.ERRORS -> stringResource(R.string.settings_logs_filter_errors)
        SettingsLogFilter.WARNINGS -> stringResource(R.string.settings_logs_filter_warnings)
        SettingsLogFilter.SMS_PIPELINE -> stringResource(R.string.settings_logs_filter_sms)
        SettingsLogFilter.UPDATES -> stringResource(R.string.settings_logs_filter_updates)
    }

@Composable
private fun LogEmptyState() {
    MasroofCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap),
        ) {
            IconLabelRow(
                icon = MasroofIcons.recentTransactions,
                label = stringResource(R.string.settings_logs_empty_title),
                iconTint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.settings_logs_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LogFilteredEmptyState() {
    MasroofCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_logs_filter_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LogEntryCard(
    entry: AppLogEntry,
    locale: Locale,
    zoneId: ZoneId,
    timestampLabels: LogTimestampLabels,
) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val extendedColors = MasroofThemeExtras.extendedColors
    val accentColor = when (entry.level) {
        AppLogLevel.INFO -> extendedColors.inflow
        AppLogLevel.WARN -> extendedColors.liability
        AppLogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    val timestamp = remember(entry.timestampEpochMs, timestampLabels) {
        formatLogTimestamp(entry.timestampEpochMs, zoneId, locale, timestampLabels)
    }

    MasroofCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogLevelPill(level = entry.level, accentColor = accentColor)
            Text(
                timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(logCategoryLabel(entry.category), modifier = Modifier.padding(top = MasroofSpacing.cardInnerGap),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            entry.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = MasroofSpacing.cardInnerGap),
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(R.string.reference_event_details))
        }
        if (expanded) {
            com.baraa.masroof.presentation.common.MasroofDetailRow(stringResource(R.string.reference_event_id), entry.id.toString())
            com.baraa.masroof.presentation.common.MasroofDetailRow(stringResource(R.string.reference_event_time),
                java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", locale).format(java.time.Instant.ofEpochMilli(entry.timestampEpochMs).atZone(zoneId)))
        }
    }
}

@Composable
private fun LogLevelPill(
    level: AppLogLevel,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = accentColor.copy(alpha = MasroofOpacity.badge),
    ) {
        Text(
            stringResource(logLevelLabelRes(level)),
            modifier = Modifier.padding(horizontal = MasroofSpacing.cardInnerGap, vertical = MasroofSpacing.inlineGap),
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
        )
    }
}

@Composable
private fun resolveLogMessage(message: LogMessage): String =
    when (message) {
        LogMessage.EXPORT_SUCCESS -> stringResource(R.string.settings_logs_export_success)
        LogMessage.EXPORT_FAILED -> stringResource(R.string.settings_logs_export_failed)
        LogMessage.CLEARED -> stringResource(R.string.settings_logs_cleared)
    }
