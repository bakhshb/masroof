package com.baraa.masroof.application.analytics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.baraa.masroof.analytics.*
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.commitment.CommitmentCreationResult
import com.baraa.masroof.application.dashboard.CommitmentPaymentStatus
import com.baraa.masroof.application.dashboard.AnalysisSpendingInsights
import com.baraa.masroof.application.dashboard.AnalysisSpendingFact
import com.baraa.masroof.application.dashboard.AnalysisSpendingInsightsBuilder
import com.baraa.masroof.application.dashboard.ChartDataCategory
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.analytics.*
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.MasroofApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

/** Read-only financial adapter; advisory writes target only AnalyticsDatabase. */
class AnalyticsWorkflow(private val container: AppContainer, private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone()) : AutoCloseable {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private var refreshJob: Job? = null
    private var clockJob: Job? = null
    private var importJob: Job? = null
    @Volatile private var closed = false
    private val stopSignal = CompletableDeferred<Unit>()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val storeDelegate = lazy { AnalyticsDatabase.open(context) }
    private val store by storeDelegate
    private val json = AnalyticsJson.format
    private val mutable = MutableStateFlow(AnalysisUiState())
    val state: StateFlow<AnalysisUiState> = mutable.asStateFlow()
    private var started = false
    val appLogService: com.baraa.masroof.application.logging.AppLogService get() = container.appLogService
    private val onboardingPrefs = context.getSharedPreferences(com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository.PREFS_NAME, Context.MODE_PRIVATE)
    private val localePrefs = context.getSharedPreferences(com.baraa.masroof.data.preferences.SharedPrefsAppLocaleRepository.PREFS_NAME, Context.MODE_PRIVATE)
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> request() }
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val observer = object : InvalidationTracker.Observer("financial_transaction", "financial_transaction_raw_sms_link",
        "parsed_event", "commitment", "account_registry", "card_registry", "loan_registry", "credit_facility", "processing_retry", "review_item", "user_correction") {
        override fun onInvalidated(tables: Set<String>) { request() }
    }
    @Synchronized fun start() {
        if (started || closed) return
        started = true
        onboardingPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        localePrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        refreshJob = scope.launch {
            while (!closed && container.awaitStartupMaintenance() != StartupMaintenanceOutcome.READY) {
                withTimeoutOrNull(5_000) { stopSignal.await() }
            }
            if (closed) return@launch
            container.database.invalidationTracker.addObserver(observer)
            try { loadCached() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                container.appLogService.failure("LAB_ANALYTICS", "Load cached analysis", failure)
            }
            request()
            for (ignored in changes) {
                if (closed) break
                withTimeoutOrNull(1200) { stopSignal.await() }
                if (closed) break
                while (changes.tryReceive().isSuccess) { /* coalesce committed batches */ }
                try { refresh() } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    container.appLogService.error("LAB_ANALYTICS", "Analysis failed (${e.javaClass.simpleName})")
                    mutable.update { it.copy(updating = false, unavailable = true) }
                }
            }
        }
        clockJob = scope.launch {
            var key = clockKey()
            while (isActive) { delay(millisUntilClockBoundary()); val current = clockKey(); if (current != key) { key = current; request() } }
        }
        AnalyticsWorker.scheduleDaily(context)
        importJob = scope.launch { AnalyticsImportGate.completed.collect { request() } }
    }
    /** Unobserved fingerprint inputs: the local day, the 6-hour bucket and SMS permission. Cheap to read each minute. */
    internal fun clockKey() = Triple(LocalDate.now(clock), clock.millis() / SIX_HOURS_MS, smsPermissionMissing())
    private fun millisUntilClockBoundary(): Long {
        val now = clock.millis()
        val midnight = LocalDate.now(clock).plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val bucket = (now / SIX_HOURS_MS + 1) * SIX_HOURS_MS
        // Capped at a minute: delay() does not count deep sleep, and a permission change has no callback.
        return (minOf(midnight, bucket) - now).coerceIn(1_000L, 60_000L)
    }
    private fun periodOffset(day: Long): Int =
        (day - FinancialPeriodPolicy.periodContaining(LocalDate.ofEpochDay(day)).startDate.toEpochDay()).toInt()
    private fun smsPermissionMissing() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED
    fun request() { if (closed) return; generation.incrementAndGet(); mutable.update { it.copy(updating = true) }; changes.trySend(Unit) }

    suspend fun messageReviewExamples() = withContext(Dispatchers.IO) {
        container.databaseAccessGate.withAccess { container.messageReviewWorkflow.load() }
    }

    private suspend fun loadCached() {
        val dao = store.analytics()
        dao.initializePreferences(AnalysisPreferencesRow(enabledAt = clock.millis()))
        val prefs = requireNotNull(dao.preferences())
        val cached = dao.latest()?.let { runCatching { json.decodeFromString<AnalysisSnapshot>(it.payload) }.getOrNull() }
        val history = dao.recentSnapshots().mapNotNull { runCatching { json.decodeFromString<AnalysisSnapshot>(it.payload) }.getOrNull() }
        val comparisons = dao.comparisons()
        val learning = try { container.learningInsightsWorkflow.load() } catch(e: CancellationException) { throw e }
            catch(_: Exception) { com.baraa.masroof.application.interpretation.LearningInsights(pending = true) }
        val candidates = dao.candidates().mapNotNull { runCatching { json.decodeFromString<AnalysisCandidate>(it.payload) }.getOrNull() }
        val corrections = dao.feedback().filter { it.kind in setOf("NOT_RECURRING", "DISMISSED") }.sortedByDescending { it.decidedAt }.mapNotNull { row ->
            candidates.firstOrNull { it.seriesKey == row.seriesKey }?.let { AnalysisRecurringCorrection(row.seriesKey, it.name, row.kind) }
        }
        mutable.value = AnalysisUiState(cached, true, false, dao.alerts().filterNot { it.resolved }, prefs, history,
            forecastPerformance = ForecastPerformancePolicy.evaluate(comparisons), forecastComparisons = comparisons, learning = learning,
            recurringCorrections = corrections)
    }

    private data class Source(val clean: CleanData, val start: Long, val end: Long, val today: Long,
        val completed: Int, val dues: List<AnalysisDue>, val committed: Set<String>, val reviews: Int,
        val blocked: Boolean, val fingerprint: String, val reasons: List<String>, val insights: AnalysisSpendingInsights,
        val committedCadences: Map<String, String>)

    private suspend fun source(): Source {
            val zone = clock.zone; val today = LocalDate.now(clock); val todayDay = today.toEpochDay()
            val period = FinancialPeriodPolicy.periodContaining(today)
            val start = today.minusYears(1)
            val read = FinancialAnalysisReader.capture(container, start.atStartOfDay(zone).toInstant(), period.endDateExclusive.atStartOfDay(zone).toInstant(), zone, clock)
            val historical = read.service.loadOverview(FinancialPeriod(start, period.endDateExclusive))
            val current = read.service.loadOverview(period)
            val included = historical.spendingTrends?.purchaseScopeTransactionIds.orEmpty()
            val raw = read.rows.associateBy { it.id }
            val facts = historical.transactions.map { tx ->
                val original = raw[tx.id] ?: tx
                val amount = runCatching {
                    val decimal = if (original.amount.currency == Currency.SAR) original.amount.amount else {
                        val appliedRate = tx.appliedExchangeRate
                        if (appliedRate == null || tx.exchangeRateSource == null || appliedRate.signum() <= 0) null
                        else historical.labSarEquivalents[tx.id]?.amount
                    }
                    decimal?.setScale(2, RoundingMode.HALF_EVEN)?.movePointRight(2)?.longValueExact()
                }.getOrNull()
                val effective = historical.transactionFacts[tx.id]?.effectiveType ?: tx.type
                FinancialFact(tx.id, tx.occurredAt.atZone(zone).toLocalDate().toEpochDay(), amount,
                    tx.type == FinancialTransactionType.REFUND, tx.id in included, tx.merchant ?: tx.counterparty,
                    effective in setOf(FinancialTransactionType.EXPENSE, FinancialTransactionType.FEE, FinancialTransactionType.BILL_PAYMENT),
                    tx.sourceContainerId ?: tx.destinationContainerId,
                    (tx.sourceContainerId ?: tx.destinationContainerId)?.let { "$it|${original.amount.currency}|${effective.name}" })
            }
            val onboarding = container.onboardingPreferencesRepository
            val importedFrom = if (onboarding.isHistoricalImportCompleted()) onboarding.getHistoricalImportStartEpochMillis()
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() } else null
            // A successful historical import also covers days with no financial rows.
            val covered = importedFrom
            val retry = read.retry
            val noPermission = smsPermissionMissing()
            val importBusy = AnalyticsImportGate.active()
            val blocked = retry || importBusy || !onboarding.isHistoricalImportCompleted() || noPermission
            store.analytics().initializePreferences(AnalysisPreferencesRow(enabledAt = clock.millis()))
            var prefs = requireNotNull(store.analytics().preferences())
            val coverageRows = store.analytics().coverage().toMutableList()
            val at = clock.millis()
            for(day in prefs.gapDays.split(',').mapNotNull { it.toLongOrNull() }) {
                if(coverageRows.none { it.id == "legacy:$day" || (it.reason !in setOf("SCAN_RECEIPT","CHECKPOINT") && day in it.fromDay..it.throughDay) })
                    coverageRows += AnalysisCoverageRow("legacy:$day",day,day,"LEGACY",LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli().coerceAtLeast(0))
            }
            val checkpoint = coverageRows.firstOrNull { it.id == "last-check" }
            if(noPermission || checkpoint?.repairedAt == null && checkpoint != null) {
                val previous = coverageRows.lastOrNull { it.reason == "PERMISSION" && it.repairedAt == null }
                if(previous != null) {
                    coverageRows.remove(previous)
                    coverageRows += previous.copy(throughDay = maxOf(previous.throughDay,todayDay))
                } else if(noPermission) {
                    val from = checkpoint?.throughDay ?: Instant.ofEpochMilli(prefs.enabledAt).atZone(zone).toLocalDate().toEpochDay()
                    coverageRows += AnalysisCoverageRow("permission:$at",minOf(from,todayDay),todayDay,"PERMISSION",at)
                }
            }
            for(day in read.incompleteDays) {
                val prior = coverageRows.lastOrNull { it.reason == "PROCESSING" && it.fromDay == day && it.repairedAt == null }
                if(prior == null) coverageRows += AnalysisCoverageRow("processing:$day:$at",day,day,"PROCESSING",at)
            }
            coverageRows.removeAll { it.id == "last-check" }
            coverageRows += AnalysisCoverageRow("last-check",todayDay,todayDay,"CHECKPOINT",at,if(noPermission) null else at)
            val unresolved = read.incompleteDays + if(noPermission) AnalysisCoveragePolicy.gapDays(coverageRows) else emptySet()
            val recovered = AnalysisCoveragePolicy.recover(coverageRows,unresolved,at)
            store.analytics().coverage(recovered)
            val gaps = AnalysisCoveragePolicy.gapDays(recovered)
            val gapText = gaps.sorted().joinToString(",")
            if(gapText != prefs.gapDays) {
                prefs = prefs.copy(gapDays = gapText)
                store.analytics().preferences(prefs)
            }
            val clean = FinancialCleaner.clean(facts, start.toEpochDay(), todayDay, covered, gaps, ::periodOffset)
            val completed = completedAnalysisPeriods(clean, todayDay)
            val categories = historical.spendingTrends?.purchases?.categoryTrends.orEmpty()
            val cashIds = categories[ChartDataCategory.CASH_WITHDRAWALS]?.transactionIdsByDate?.values?.flatten()?.toSet().orEmpty()
            val transferIds = categories[ChartDataCategory.EXTERNAL_TRANSFERS]?.transactionIdsByDate?.values?.flatten()?.toSet().orEmpty()
            val invalidIds = clean.quality.excluded.filterKeys { it != "DUPLICATE_ID" }.values.flatten().toSet()
            val insightFacts = facts.distinctBy { it.id }.filter { it.included && it.id !in invalidIds && it.amount != null }.map {
                AnalysisSpendingFact(it.id, it.day, if (it.refund) Math.negateExact(it.amount!!) else it.amount!!,
                    when { it.refund -> "REFUNDS"; it.id in cashIds -> "CASH"; it.id in transferIds -> "TRANSFERS"; else -> "PURCHASES" }, it.merchant)
            }
            val previousPeriod = FinancialPeriodPolicy.previous(period)
            val insights = AnalysisSpendingInsightsBuilder.build(insightFacts, period.startDate.toEpochDay(), todayDay,
                previousPeriod.startDate.toEpochDay(), previousPeriod.endDateExclusive.toEpochDay(),
                clean.days.filter { it.covered }.map { it.day }.toSet())
            val reasons = buildList {
                if (noPermission) add("SMS_PERMISSION")
                if (retry) add("PROCESSING_RETRY")
                if (!onboarding.isHistoricalImportCompleted()) add("IMPORT_PENDING")
                if (importBusy) add("IMPORT_RUNNING")
                if (current.summary.reviewRequiredCount > 0) add("PENDING_REVIEW")
                if (gaps.any { it in period.startDate.toEpochDay()..todayDay }) add("COVERAGE_GAPS")
                addAll(clean.quality.excluded.keys.filter { it !in setOf("OUTSIDE_PURCHASE_SCOPE", "DUPLICATE_ID") })
            }.distinct()
            val dues = current.commitmentsOverview.rows.filter { it.status == CommitmentPaymentStatus.UNPAID }.mapNotNull {
                if (it.amount.currency != Currency.SAR) null else AnalysisDue(it.key, it.displayName,
                    it.amount.amount.movePointRight(2).longValueExact(), it.expectedDate?.toEpochDay(), true)
            }
            val committed = read.committed
            val decisions = store.analytics().candidates().filter { it.status != "SUGGESTED" }.sortedBy { it.id }.map { it.id to it.status }
            val feedback = store.analytics().feedback().joinToString { "${it.seriesKey}:${it.kind}:${it.decidedAt}:${hash(it.payload)}" }
            val canonical = "continuous-learning-v1|${RecurringLearningWorkflow.version}|${ForecastPerformancePolicy.VERSION}|${AnalyticsVersion.EVALUATION}|${AnalyticsVersion.MODEL}|${AnalyticsVersion.FEATURES}|${clean.fingerprint}|$dues|${committed.sorted()}|$blocked|${current.summary.reviewRequiredCount}|${period.startDate}|$prefs|$decisions|$feedback|${read.committedCadences}|${clock.millis() / SIX_HOURS_MS}"
            return Source(clean, period.startDate.toEpochDay(), period.endDateExclusive.toEpochDay(), todayDay, completed,
                dues, committed, current.summary.reviewRequiredCount, blocked, hash(canonical), reasons, insights, read.committedCadences)
    }

    suspend fun refresh() = withContext(Dispatchers.Default) { refreshOnWorker() }
    private suspend fun refreshOnWorker() = mutex.withLock {
        if (closed) return@withLock
        if (container.awaitStartupMaintenance() != StartupMaintenanceOutcome.READY) return@withLock
        if (AnalyticsImportGate.active()) return@withLock // import completion calls request()
        val version = generation.get()
        val input = source()
        val dao = store.analytics(); val prefs = dao.preferences() ?: AnalysisPreferencesRow(enabledAt = clock.millis())
        val comparisons = settleComparisons(input)
        val performance = ForecastPerformancePolicy.evaluate(comparisons)
        val latest = dao.latest()
        val captureMissing = ForecastPerformancePolicy.eligibleOrigin(input.start,input.end,input.today) &&
            !input.blocked && input.reasons.isEmpty() && !ForecastPerformancePolicy.hasComparison(comparisons,input.start)
        if (latest?.sourceHash == input.fingerprint && !captureMissing) { loadCached(); finishUi(input); return@withLock }
        val previous = dao.models().filterNot { it.id.startsWith("baseline:") }.mapNotNull { runCatching { json.decodeFromString<StoredHuber>(it.payload).takeIf { it.modelVersion == AnalyticsVersion.MODEL && it.cleaningVersion == AnalyticsVersion.CLEANING && it.evaluationVersion == AnalyticsVersion.EVALUATION }?.model() }.getOrNull() }.maxByOrNull { it.trainedThrough }
        val result = PurchaseForecaster.forecast(input.clean, input.start, input.end, input.today, input.completed,
            ::periodOffset, previous, { before -> completedAnalysisPeriods(input.clean, before) }, allowPersonal = performance.allowPersonal)
        val feedback = dao.feedback().map { row ->
            if (row.kind != "CONFIRM_PENDING") row else {
                val payload = json.decodeFromString<RecurringFeedbackPayload>(row.payload)
                if (payload.label != null && input.committedCadences[payload.sourceIds.last()] == payload.label) {
                    row.copy(kind = "CONFIRMED").also { dao.feedback(it) }
                } else row
            }
        }
        val recurring = RecurringLearningWorkflow.build(input.clean.recurring, feedback, input.today, input.committedCadences)
        val (patterns, recurringStatus) = recurring
        val priorCandidates = dao.candidates().associateBy { it.id }
        val legacy = priorCandidates.values.filter { it.status != "SUGGESTED" }.mapNotNull { row ->
            runCatching { json.decodeFromString<AnalysisCandidate>(row.payload) }.getOrNull()?.takeIf { it.seriesKey == null }?.let { row to it }
        }
        val candidates = patterns.map { pattern ->
            AnalysisCandidate(pattern.key, pattern.name, pattern.estimate, pattern.lower, pattern.upper, pattern.nextDay,
                pattern.cadence, pattern.sourceIds, input.clean.recurring.first { it.id == pattern.sourceIds.last() }.day,
                pattern.latestAmount, pattern.referenceAmount, pattern.amountIncreased, pattern.nextDay < input.today,
                pattern.seriesKey, pattern.origin, pattern.modelVersion)
        }
        fun status(item: AnalysisCandidate) = priorCandidates[item.key]?.status ?: legacy.firstOrNull { (_, old) -> old.sourceIds.any { it in item.sourceIds } }?.first?.status
        val suggested = candidates.filter { item -> item.sourceIds.none { it in input.committed } && status(item) !in setOf("ACCEPTED", "REJECTED") }
        val estimates = candidates.filter { item -> item.nextDay in input.today until input.end && item.sourceIds.none { it in input.committed } && status(item) != "REJECTED" }
            .map { AnalysisDue(it.key, it.name, it.amount, it.nextDay, false) }
        val at = clock.millis()
        val quality = AnalysisQuality(input.clean.quality.used, input.clean.quality.excluded, input.clean.quality.unusualIds,
            input.clean.quality.coveredDays, input.clean.quality.coverageKnown, input.reviews,
            input.blocked || input.reasons.isNotEmpty(),
            input.clean.days.firstOrNull { it.covered }?.day, input.clean.days.lastOrNull { it.covered }?.day, input.reasons,
            AnalysisPresentationBuilder.coverage(input.clean, input.today), AnalysisPresentationBuilder.consecutiveCompleteDays(input.clean, input.today))
        val todayFacts = input.clean.days.first { it.day == input.today }
        val futureDays = result.points.map { point -> AnalysisDay(point.day, null,
            if (point.day == input.today) Math.addExact(todayFacts.net, (point.gross - todayFacts.gross).coerceAtLeast(0)) else point.gross) }
        val snapshot = AnalysisSnapshot(input.start, input.end, at, input.fingerprint, result.status, result.method,
            result.actual, result.remaining, result.total, result.lower, result.upper,
            input.clean.days.filter { it.day >= input.start }.map { AnalysisDay(it.day, it.net, null) } + futureDays,
            quality, suggested, input.dues + estimates, input.completed,
            trainingFrom = if (result.method == AnalyticsVersion.MODEL) result.model?.trainedFrom else result.baseline?.from,
            trainingThrough = if (result.method == AnalyticsVersion.MODEL) result.model?.trainedThrough else result.baseline?.through,
            insights = input.insights, evaluations = result.evaluations.map { AnalysisEvaluation(it.horizon, it.count, it.mae, it.bias) },
            modelReason = result.reason, evaluationVersion = AnalyticsVersion.EVALUATION,
            wholeDaysFrom = (input.today + 1).takeIf { it < input.end }, wholeDaysLower = result.wholeDaysLower,
            wholeDaysUpper = result.wholeDaysUpper, budgetLower = result.wholeDaysLower?.let { Math.addExact(result.actual, it) },
            recurringLearning = recurringStatus)
        // A committed source invalidation while fitting invalidates the entire result.
        // Room tables, preferences, import completion and the clock bump generation; SMS permission does not.
        if (generation.get() != version || ("SMS_PERMISSION" in input.reasons) != smsPermissionMissing()) { request(); return@withLock }
        val alerts = alerts(snapshot, candidates.filter { status(it) != "REJECTED" }, dao.alerts(), prefs)
        store.withTransaction {
            dao.snapshot(AnalysisSnapshotRow(hash("$at|${input.fingerprint}"), input.fingerprint, at, json.encodeToString(snapshot)))
            if(ForecastPerformancePolicy.eligibleOrigin(input.start,input.end,input.today) && !input.blocked && input.reasons.isEmpty() &&
                result.personalTotal != null && result.baselineTotal != null &&
                !ForecastPerformancePolicy.hasComparison(dao.comparisons(),input.start)) {
                dao.comparison(ForecastComparisonRow("${input.start}:${AnalyticsVersion.MODEL}:${ForecastPerformancePolicy.VERSION}",
                    input.start,input.end,at,input.fingerprint,AnalyticsVersion.MODEL,ForecastPerformancePolicy.VERSION,
                    requireNotNull(result.personalTotal),requireNotNull(result.baselineTotal),originDay = input.today))
            }
            result.model?.let {
                val payload = json.encodeToString(StoredHuber.from(it))
                dao.model(AnalysisModelRow(hash(payload), payload))
            }
            result.baseline?.let {
                val payload = json.encodeToString(StoredBaseline(it.medians, it.from, it.through, it.errors))
                dao.model(AnalysisModelRow("baseline:${hash(payload)}", payload))
            }
            dao.candidates(candidates.map { c -> RecurringCandidateRow(c.key,
                status(c) ?: if (c.sourceIds.any { it in input.committed }) "ACCEPTED" else "SUGGESTED", json.encodeToString(c)) })
            dao.alerts(alerts)
        }
        if (generation.get() != version) { request(); return@withLock }
        loadCached(); finishUi(input)
        if (generation.get() == version) AnalyticsNotifications.publish(
            com.baraa.masroof.application.locale.AppLocaleContextFactory.wrap(context, container.appLocaleRepository.getLanguageTag()), store, snapshot, prefs, clock.millis())
    }
    private suspend fun settleComparisons(input: Source): List<ForecastComparisonRow> {
        val dao = store.analytics()
        for(row in dao.comparisons().filter { it.periodEnd <= input.today }) {
            val days = input.clean.days.filter { it.day in row.periodStart until row.periodEnd }
            val complete = days.size.toLong() == row.periodEnd - row.periodStart && days.all { it.covered }
            val actual = if(complete) days.fold(0L) { sum,day -> Math.addExact(sum,day.net) } else null
            dao.evaluateComparison(row.id,actual,input.clean.fingerprint,if(actual == null) null else clock.millis())
        }
        return dao.comparisons()
    }

    private fun finishUi(input: Source) {
        val actuals = mutable.value.history.filter { it.periodEnd <= input.today }.distinctBy { it.periodStart }.mapNotNull { past ->
            val days = input.clean.days.filter { it.day in past.periodStart until past.periodEnd }
            if (days.size.toLong() != past.periodEnd - past.periodStart || days.any { !it.covered }) null
            else past.periodStart to days.fold(0L) { sum, day -> Math.addExact(sum, day.net) }
        }.toMap()
        val observations = if (input.blocked || input.reasons.isNotEmpty() || !input.clean.quality.coverageKnown) emptyList() else
            SpendingObservationBuilder.build(input.clean.recurring, input.start, input.today,
                input.clean.days.filter { it.covered }.map { it.day }.toSet())
        mutable.update { it.copy(updating = false, unavailable = false, historyActuals = actuals,
            purchasePeriods = purchasePeriodHistory(input.clean, input.start), observations = observations) }
    }

    private fun alerts(snapshot: AnalysisSnapshot, candidates: List<AnalysisCandidate>, previous: List<AnalysisAlertRow>, prefs: AnalysisPreferencesRow) =
        AnalysisAlertPolicy.evaluate(snapshot, candidates, previous, prefs, LocalDate.now(clock).toEpochDay())
    suspend fun updatePreferences(value: AnalysisPreferencesRow) {
        require(value.budget == null || value.budget > 0); require(value.reminderDays in 1..30)
        mutex.withLock {
            if (closed) return@withLock
            container.databaseAccessGate.withAccess {
                val old = store.analytics().preferences()
                store.analytics().preferences(value.copy(id = 1, enabledAt = old?.enabledAt?.takeIf { it > 0 } ?: clock.millis(), gapDays = old?.gapDays.orEmpty()))
            }
        }
        request()
    }
    suspend fun findAlert(id: String): AnalysisAlertRow? = withContext(Dispatchers.IO) {
        mutex.withLock { if (closed) null else container.databaseAccessGate.withAccess {
            store.analytics().alerts().firstOrNull { it.id == id }
        } }
    }
    suspend fun markAlert(id: String, snooze: Boolean = false) = mutex.withLock {
        if (closed) return@withLock
        container.databaseAccessGate.withAccess {
            val dao = store.analytics(); val row = dao.alerts().firstOrNull { it.id == id } ?: return@withAccess
            dao.alerts(listOf(row.copy(read = !snooze, readAt = if (!snooze && row.readAt == 0L) clock.millis() else row.readAt,
                snoozedUntil = if (snooze) clock.millis() + 24 * 3600_000 else row.snoozedUntil)))
            val alerts = dao.alerts().filterNot { row -> row.resolved }
            mutable.update { it.copy(alerts = alerts) }
            AnalyticsNotifications.cancel(context, id)
        }
    }
    suspend fun rejectCandidate(key: String, notRecurring: Boolean = false) {
        mutex.withLock { if (closed) return
            container.databaseAccessGate.withAccess {
            val row = store.analytics().candidates().firstOrNull { it.id == key } ?: return@withAccess
            store.withTransaction {
                val candidate = json.decodeFromString<AnalysisCandidate>(row.payload)
                recordRecurringFeedback(candidate, if (notRecurring) "NOT_RECURRING" else "DISMISSED",
                    if (notRecurring) RecurringLabel.NONE.name else null)
                store.analytics().candidates(listOf(row.copy(status = "REJECTED")))
            }
        } }; request()
    }
    suspend fun acceptCandidate(key: String, draft: SettingsCommitmentsWorkflow.CommitmentEditorDraft): Boolean {
        val c = mutable.value.snapshot?.candidates?.firstOrNull { it.key == key } ?: return false
        require(draft.name.isNotBlank() && draft.amount.amount.signum() > 0)
        mutex.withLock { if (closed) return false
            container.databaseAccessGate.withAccess {
            recordRecurringFeedback(c, "CONFIRM_PENDING", draft.recurrence?.name?.takeIf { it in setOf("WEEKLY", "MONTHLY") })
            container.database.withTransaction {
                val source = container.financialTransactionRepository.getById(c.sourceIds.last()) ?: error("Source changed")
                val existing = container.commitmentRepository.getBySourceTransactionId(source.id)
                if (existing == null) {
                    check(container.dashboardCommitmentsWorkflow.createFromTransaction(source.id) is CommitmentCreationResult.Success)
                    val created = requireNotNull(container.commitmentRepository.getBySourceTransactionId(source.id))
                    container.settingsCommitmentsWorkflow.update(created.id, draft)
                }
            }
            val row = store.analytics().candidates().firstOrNull { it.id == key }
            val saved = container.commitmentRepository.getBySourceTransactionId(c.sourceIds.last())
            val learnedCadence = draft.recurrence?.name?.takeIf { it in setOf("WEEKLY", "MONTHLY") && saved?.recurrence?.name == it }
            store.withTransaction {
                recordRecurringFeedback(c, "CONFIRMED", learnedCadence)
                if (row != null) store.analytics().candidates(listOf(row.copy(status = "ACCEPTED")))
            }
        } }
        request(); return true
    }

    private suspend fun recordRecurringFeedback(candidate: AnalysisCandidate, kind: String, label: String?) {
        val seriesKey = candidate.seriesKey ?: return
        val payload = RecurringFeedbackPayload(seriesKey, candidate.sourceIds, label).also { it.validate() }
        store.analytics().feedback(RecurringFeedbackRow(seriesKey, kind, json.encodeToString(payload), clock.millis()))
    }

    suspend fun undoRecurringCorrection(seriesKey: String) = mutex.withLock {
        if (closed) return@withLock
        container.databaseAccessGate.withAccess {
            store.withTransaction {
                val dao = store.analytics()
                val row = dao.feedback().firstOrNull { it.seriesKey == seriesKey && it.kind in setOf("NOT_RECURRING", "DISMISSED") } ?: return@withTransaction
                dao.deleteFeedback(row.seriesKey)
                val candidates = dao.candidates().filter { it.status == "REJECTED" }.filter {
                    runCatching { json.decodeFromString<AnalysisCandidate>(it.payload).seriesKey == seriesKey }.getOrDefault(false)
                }
                dao.candidates(candidates.map { it.copy(status = "SUGGESTED") })
            }
        }
        request()
    }

    /** Bounded, live read; projection and evidence enrichment happen outside the financial read transaction. */
    suspend fun evidence(ids: List<String>): List<AnalysisEvidence> = withContext(Dispatchers.IO) {
        require(ids.size <= 20)
        container.databaseAccessGate.withAccess {
            val rows = ids.distinct().mapNotNull { container.financialTransactionRepository.getById(it) }
            val facts = if (rows.isEmpty()) emptyMap() else {
                val from = rows.minOf { it.occurredAt }.atZone(clock.zone).toLocalDate()
                val through = rows.maxOf { it.occurredAt }.atZone(clock.zone).toLocalDate().plusDays(1)
                FinancialAnalysisReader.capture(container, from.atStartOfDay(clock.zone).toInstant(), through.atStartOfDay(clock.zone).toInstant(), clock.zone, clock)
                    .service.loadOverview(FinancialPeriod(from, through)).transactionFacts
            }
            val byId = rows.associateBy { it.id }
            val cards = container.cardRegistryRepository.listAll().filter { it.ownership == com.baraa.masroof.domain.model.OwnershipStatus.OWNED }
            ids.distinct().sortedWith(compareByDescending<String> { byId[it]?.occurredAt }.thenBy { it })
                .map { AnalysisEvidence(it, byId[it], facts[it], cards) }
        }
    }
    suspend fun sortEvidenceIds(ids: List<String>): List<String> = withContext(Dispatchers.IO) {
        container.databaseAccessGate.withAccess {
            val dates = mutableMapOf<String, Long>()
            // Only read ordering metadata for the referenced IDs, in bounded SQLite batches.
            ids.distinct().chunked(400).forEach { batch ->
                val placeholders = batch.joinToString(",") { "?" }
                container.database.openHelper.readableDatabase.query(
                    "SELECT id, occurredAtEpochMillis FROM financial_transaction WHERE id IN ($placeholders)", batch.toTypedArray()
                ).use { cursor -> while (cursor.moveToNext()) dates[cursor.getString(0)] = cursor.getLong(1) }
            }
            ids.distinct().sortedWith(compareByDescending<String> { dates[it] }.thenBy { it })
        }
    }
    fun comparisonExplanation() = AnalysisPresentationBuilder.comparison(state.value, LocalDate.now(clock).toEpochDay(), clock.zone)

    internal suspend fun replayForecasts(): List<ForecastReplayRow> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withLock emptyList()
            val input = source()
            purchasePeriodHistory(input.clean, input.start).filter { it.complete }.map { period ->
                val origin = period.start + 7
                ForecastReplay.evaluate(input.clean, period.start, period.end, origin, completedAnalysisPeriods(input.clean, origin),
                    ::periodOffset,
                    { day -> completedAnalysisPeriods(input.clean, day) })
            }
        }
    }

    /** Explicit developer-only export; no UI, network upload, names, dates or monetary amounts. */
    internal suspend fun exportReviewedRecurrence(contributorGroup: String): String = withContext(Dispatchers.Default) {
        require(contributorGroup.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        mutex.withLock {
            if (closed) return@withLock ""
            val input = source()
            val result = RecurringLearningWorkflow.build(input.clean.recurring, store.analytics().feedback(), input.today, input.committedCadences)
            result.reviewedExamples.sortedBy { it.seriesKey }.joinToString("\n", postfix = "\n") { row ->
                listOf(hash("$contributorGroup|${row.seriesKey}"), contributorGroup, "train", row.label.name,
                    result.ruleLabels.getValue(row.seriesKey).name).joinToString("\t") + "\t" + row.features.joinToString("\t")
            }
        }
    }

    fun money(cents: Long): Money = Money(BigDecimal.valueOf(cents, 2), Currency.SAR)
    fun budgetStatus(): String {
        val s = state.value.snapshot ?: return "NONE"
        val budget = state.value.preferences.budget ?: return "NONE"
        return when {
            s.actual > budget -> "ACTUAL"
            s.lower != null && s.lower > budget -> "EXPECTED"
            s.actual.toDouble() >= budget * .8 || (s.total ?: 0) >= budget -> "NEAR"
            else -> "WITHIN"
        }
    }
    fun editorDraft(candidate: AnalysisCandidate) = SettingsCommitmentsWorkflow.CommitmentEditorDraft(
        candidate.name, money(candidate.amount), LocalDate.ofEpochDay(candidate.latestDay),
        com.baraa.masroof.domain.model.CommitmentRecurrence.valueOf(candidate.cadence), LocalDate.ofEpochDay(candidate.nextDay))
    fun parseBudget(value: String): Long? = value.trim().takeIf { it.isNotEmpty() }?.let { BigDecimal(normalizeAnalysisNumber(it)).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().also { require(it > 0) } }
    override fun close() {
        if (closed) return
        closed = true
        stopSignal.complete(Unit)
        onboardingPrefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        localePrefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        if (started) container.database.invalidationTracker.removeObserver(observer)
        changes.close()
        clockJob?.cancel()
        importJob?.cancel()
        runBlocking {
            refreshJob?.join()
            mutex.withLock { }
            scopeJob.complete()
            scopeJob.join()
        }
        if (storeDelegate.isInitialized()) store.close()
    }
    companion object {
        private const val SIX_HOURS_MS = 6 * 3600_000L
        fun from(context: Context): AnalyticsWorkflow = (context.applicationContext as MasroofApplication).container.analytics
    }
}

/** Scanner hook is process-local; persisted retry/import-completion gates cover restart. */
object AnalyticsImportGate {
    private val count = java.util.concurrent.atomic.AtomicInteger()
    private val revision = MutableStateFlow(0L)
    val completed = revision.asStateFlow()
    fun active() = count.get() > 0
    suspend fun <T> during(block: suspend () -> T): T { count.incrementAndGet(); return try { block() } finally { if (count.decrementAndGet() == 0) revision.update { it + 1 } } }
}
