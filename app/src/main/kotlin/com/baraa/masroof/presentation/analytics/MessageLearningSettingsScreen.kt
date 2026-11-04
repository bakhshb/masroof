package com.baraa.masroof.presentation.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxWidth
import com.baraa.masroof.R
import com.baraa.masroof.application.analytics.AnalyticsWorkflow
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.ui.theme.MasroofSpacing

@Composable
fun MessageLearningSettingsScreen(onOpenPatterns: () -> Unit = {}, onOpenExample: (String) -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val workflow = LocalAnalyticsWorkflow.current ?: remember(context) { AnalyticsWorkflow.from(context) }
    val state by workflow.state.collectAsState()
    var examples by remember { mutableStateOf<List<com.baraa.masroof.application.interpretation.MessageReviewExample>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(workflow, state.snapshot?.sourceHash, state.learning, reload) {
        failed = false
        examples = null
        try { examples = workflow.messageReviewExamples() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { examples = null; failed = true }
    }
    if (session) MasroofBottomSheet(stringResource(R.string.personal_review_title), { session = false }, { session = false },
        stringResource(R.string.lab_analysis_close)) {
        Text(stringResource(R.string.personal_review_scope), style = MaterialTheme.typography.bodySmall)
        if (failed) Text(stringResource(R.string.personal_review_failed))
        else if (examples == null) Text(stringResource(R.string.learning_pending))
        else if (examples!!.isEmpty()) Text(stringResource(R.string.personal_review_empty))
        examples.orEmpty().forEachIndexed { index, example ->
            MasroofOutlinedButton(onClick = { session = false; onOpenExample(example.reviewId) }, modifier = Modifier.fillMaxWidth()
                .testTag("learning-example-${example.reviewId}")) {
                Text(stringResource(R.string.personal_review_example, index + 1, timestamp(example.receivedAt)))
            }
            if (example.unsupportedWording) Text(stringResource(R.string.personal_review_wording), style = MaterialTheme.typography.bodySmall)
        }
    }
    MasroofSecondaryScaffold(stringResource(R.string.analysis_learning_settings), onBack, stringResource(R.string.settings_back)) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            // The screen title already names the page; only its scope explanation sits beside the patterns entry.
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                MasroofOutlinedButton(onClick = onOpenPatterns, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pattern_title)) }
                MasroofGuidanceButton(stringResource(R.string.analysis_learning_settings), stringResource(R.string.settings_corrections_scope))
            }
            MessageLearningInsights(state)
            MasroofCard {
                MasroofSectionHeader(stringResource(R.string.personal_review_title))
                Text(stringResource(R.string.personal_review_scope), style = MaterialTheme.typography.bodySmall)
                when {
                    failed -> {
                        Text(stringResource(R.string.personal_review_failed))
                        MasroofTextButton(onClick = { reload++ }) { Text(stringResource(R.string.dashboard_retry)) }
                    }
                    examples == null -> Text(stringResource(R.string.learning_pending))
                    examples!!.isEmpty() -> Text(stringResource(R.string.personal_review_empty))
                    else -> MasroofButton(onClick = { session = true }, modifier = Modifier.fillMaxWidth().testTag("learning-review-session")) {
                        Text(stringResource(R.string.personal_review_open, examples!!.size))
                    }
                }
            }
        }
    }
}
