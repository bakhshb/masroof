# Masroof rewrite — accepted decisions (P0)

These decisions are recorded during the clean baseline phase. They are **not**
implemented yet; they guide Phase P1+ work.

## 1. Package identity

Remains `com.baraa.masroof` (same `applicationId` / `namespace`).  
Do not introduce a `com.masroof` split or any `v2` package.

## 2. Purchase families vs channels

POS and ONLINE are **purchase channels**, not separate ownership meanings.

Conceptual shape (exact enums in the domain phase):

- `MessageFamily` includes `PURCHASE`, `TRANSFER_IN`, `TRANSFER_OUT`,
  `CARD_PAYMENT`, `BILL_PAYMENT`, `WITHDRAWAL`, `REFUND`, etc.
- `PurchaseChannel` includes `POS`, `ONLINE`, `UNKNOWN`, …

## 3. Bank network vs ownership

`INTRA_BANK` / `INTER_BANK` describe `BankNetworkType` only.

They must **never** automatically mean a self-transfer between the user's own
accounts. Ownership is resolved separately.

Example:

- Wife Bank AlJazira → User Bank AlJazira
- `MessageFamily = TRANSFER_IN`
- `BankNetworkType = INTRA_BANK`
- ownership path `EXTERNAL → OWNED`
- **not** `SELF_TRANSFER`

## 4. Persistence timing

No Room database in P0. Persistence is introduced after the domain model is
stable in a later phase.

## 5. P4 — Parse-time details vs domain ParsedEvent

Bank AlJazira fixtures assert parse-time facts that DOMAIN `ParsedEvent` does
not currently carry: `transactionReference`, `availableBalance`,
`outstandingBalance`, `biller`, `billerCode`, and offset-less local timestamps.

**Decision:** keep these on a narrowly typed parsing-layer model
`ParsedEventDetails`, attached to `ParseResult` / `ParsedEventDraft`. Do **not**
silently map biller→merchant, reference→counterparty, or balances→amount.
Do **not** extend DOMAIN.md / domain `ParsedEvent` in P4 for these fields.

## 6. P4 — Local SMS date-time vs Instant

Fixtures represent local timestamps without an offset (e.g. `2026-08-03T14:32:00`).
DOMAIN `ParsedEvent.occurredAt` is `Instant?`.

**Decision:** store local values in `ParsedEventDetails.occurredAtLocal`
(`LocalDateTime`). Leave `ParsedEvent.occurredAt` null at parse time rather than
pretending local wall time is UTC (`…Z`). Timezone policy is deferred.

## 7. P5 — Persistence schema (clean rewrite)

- Room schema **version = 1** (no legacy migrations).
- `ParsedEventDetails` stored as **nullable columns on `ParsedEventEntity`**
  (single-table atomic write); domain/parsing types remain separate via mappers.
- Money as decimal string + currency name (never Double/Float).
- Instant as epoch millis; LocalDateTime as ISO local text (no zone conversion).
- RawSms dedupe: unique `dedupeKey = sender|receivedAtEpochMillis|bodyHash`,
  plus unique nullable `deviceMessageId` (SQLite allows multiple NULLs).
- FK `parsed_event.rawSmsId → raw_sms.id` with **RESTRICT**: deleting parsed
  rows must not cascade-delete raw evidence.
- `RawSmsRepository.insertIfAbsent` is atomic via `OnConflictStrategy.IGNORE`
  (no check-then-insert race).
- `ParsedEventRepository` lives under `parsing.repository` (not domain), because
  it carries `ParsedEventDetails`.

## 8. P6 — SMS ingestion identity

- Provider inbox row → RawSms.id `android-sms:<providerId>` (stable re-scan).
- Live BroadcastReceiver (no provider id yet) →
  `android-sms-live:<sender>|<epochMillis>|<bodyHash>`.
- Cross-path dedupe relies on P5 `dedupeKey = sender|epochMillis|bodyHash`.
- Bank AlJazira scope is checked with the existing P4 detector **before**
  RawSms persistence so unrelated personal SMS are not stored.
- Historical scan processes inbox DATE ASC (oldest → newest).
- Live `RawSms.receivedAt` uses injectable device receipt [InstantClock], not SMSC
  part timestamps.
- Live↔historical near-duplicates (opposite `deviceMessageId` nullness only) may
  reconcile within a 5s receivedAt tolerance on exact sender+bodyHash; same-source
  rows are never merged by that rule alone.

## 9. P7 — Account/card ownership registry

- Schema **version = 2** with Migration(1, 2) creating `account_registry` and
  `card_registry` only (no destructive migration; P5/P6 evidence preserved).
- Account identity = `bankId + maskedNumber`; card identity = `bankId + last4`.
- Discovery observes role-aware user-side candidates as `OwnershipStatus.UNKNOWN`.
- Confirmation APIs set OWNED / EXTERNAL / clear→UNKNOWN; observation never
  overwrites explicit ownership.
- No `evidenceCount`: observation metadata is `firstSeenRawSmsId` /
  `lastSeenRawSmsId` only so backlog re-runs stay idempotent.
- Registry inserts use `OnConflictStrategy.IGNORE` (atomic create-if-absent).
- `Bank.UNKNOWN` is never persisted or confirmed in ownership registries
  (not a durable identity; cross-message linking is P8).
- `BankNetworkType` is never used to infer ownership.
- No FinancialTransaction persistence, matching, or UI in P7.

## 10. P8 — Transaction matching and financial assembly

- Schema **version = 3** with Migration(2, 3) creating `financial_transaction`
  and `financial_transaction_raw_sms_link` (links by stable `rawSmsId`).
- Conservative TRANSFER_OUT↔TRANSFER_IN matching: exact Money, 10-minute window,
  OWNED local sides, mutually unique candidates, and a strong bridge
  (exact transactionReference or UNKNOWN-suffix ↔ known-bank destination).
- Matching never mutates P7 ownership registries or Bank.UNKNOWN identities.
- Single-event assembly reuses P2 `TransactionClassifier` /
  `TransferOwnershipResolver`.
- UNKNOWN-bank transfer sides without a counterpart stay PendingMatch.
- Deterministic transaction ids from sorted RawSms ids; container ids
  `account:`/`card:` bank-scoped.
- P8 derived-processing failures must not destroy RawSms/ParsedEvent evidence.

## 11. P9 — Persistent review workflow and user resolutions

- Schema **version = 4** with Migration(3, 4) creating `review_item` and
  `user_correction` only (no destructive changes to prior tables).
- One ReviewItem per RawSms (`review:<rawSmsId>`); identity never uses
  replaceable ParsedEvent ids.
- ReviewKind: `NEEDS_REVIEW` / `PENDING_MATCH`; resolutions via
  `ReviewResolutionKind` (auto + user).
- `UserCorrection` targets `targetRawSmsId` (not ParsedEvent id); ownership
  changes stay on P7 `OwnershipConfirmationService`.
- EffectiveParsedEvent = stored ParsedEvent + latest correction overlay;
  RawSms and ParsedEvent rows remain immutable under user edits.
- P8 emits `ReconciliationReport`; `ReviewQueueUpdater` upserts REQUIRED rows
  and auto-resolves settled evidence with `AUTO_NO_LONGER_REQUIRED`.
- Manual APIs: correction, external transfer, self-transfer pair, financial type.
- No review UI / onboarding / dashboard / parsers in P9.

## 12. P10 — Onboarding + SMS setup + ownership confirmation UI

- Onboarding state is persisted outside Room using SharedPreferences
  (`onboardingCompleted`, historical import start epoch millis, import-completed).
- Android runtime permission checks remain source-of-truth; onboarding never treats
  a stored flag as permission truth.
- Historical import boundary uses selected local date at start-of-day in
  `ZoneId.systemDefault()` and passes the resulting `Instant` to P6 scanner.
- Ownership confirmation UI reads/writes only via P7 registry/services
  (`OwnershipConfirmationService`), showing discovered registry candidates only.
- Onboarding finalization triggers P9 refresh (`refreshReviewQueue`) so P8/P9
  reconciliation state is up-to-date before marking setup complete.
- P10 intentionally ships no dashboard and no review list/detail UI.

## 13. P11 — Monthly financial dashboard

- Financial month is day 27 → next day 27 exclusive (local dates), converted to
  Instant boundaries via `ZoneId.systemDefault()` start-of-day.
- Dashboard source of truth is persisted `FinancialTransaction` (+ REQUIRED review
  count). Never aggregates from RawSms/ParsedEvent text.
- Aggregation: EXPENSE+FEE → spendingGross; REFUND → refunds and reduces spendingNet;
  INCOME separate; EXTERNAL_TRANSFER_IN/OUT, SELF_TRANSFER, CREDIT_CARD_PAYMENT,
  CASH_WITHDRAWAL are separate movement buckets (not spending/income).
- ADJUSTMENT/UNKNOWN excluded from primary totals; no FX conversion; primary currency
  is SAR-scoped. `transactionCount` is total period FinancialTransaction count;
  `excludedOtherCurrencyCount` tracks rows omitted from SAR totals (always 0 while
  Currency enum is SAR-only).
- Period navigation cancels in-flight loads and never shows a stale summary under a
  different period label.
- DashboardViewModel does not preload in `init`; first load is triggered when the
  dashboard becomes visible after onboarding HOME (plus resume refresh when already
  completed).
- No account balance, net worth, budgets, categories, review UI, or full transaction
  list in P11. Room remains version 4 (DAO range query only).


## 14. Architecture hardening (SMS → dashboard)

### M0.1 — ParseStatus is a hard automation boundary

- Only `ParseStatus.SUCCESS` evidence may create, pair, heal, or post a
  `FinancialTransaction` automatically (`TransactionAssembler.isAutomationEligible`).
- `NON_FINANCIAL` is ignored for transaction creation even when the family looks financial.
- `REVIEW_REQUIRED`, `PARTIAL`, `INVALID`, `UNSUPPORTED` become `NEEDS_REVIEW`
  candidates with durable reasons (`parse_review_required`, `parse_partial`,
  `invalid_parsed_event`, `unsupported_bank_message_format`); transfers in those
  states are never paired, upgraded, or posted as external.
- An explicit user correction (`ParsedEventRecord.userCorrected`, set only by
  `EffectiveParsedEventProvider`) lifts the gate for that RawSms, as does a review
  resolved `USER_FINANCIAL_TYPE` (restore from ignored, manual single resolution).
- Existing transaction links are preserved; the gate governs creation, not deletion.

### M0.2 — Every recognized-bank RawSms has a durable outcome

- `ProcessRawSmsUseCase` writes a direct REQUIRED `NEEDS_REVIEW` row through
  `IngestionReviewService` when a persisted recognized-bank RawSms has no
  automatically usable ParsedEvent: `unsupported_bank_message_format`,
  `invalid_parsed_event`, `parse_review_required` (event == null), or
  `processing_error` (parse/persist failure after RawSms insert).
- These rows are keyed by rawSmsId, never reopen RESOLVED history, and are only
  auto-resolved when reconciliation later settles the same RawSms.
- Non-bank SMS is still not persisted and never reviewed.

### M0.3 — Reprocessing starts from RawSms evidence

- Bulk reparse (`StoredSmsReprocessor`, used by `AppContainer.reparseAllStoredEvents`
  and `ParsedEventFactsBackfillCoordinator`) iterates
  `RawSmsRepository.listIdsByReceivedAt()`, not stored ParsedEvents, so
  Unsupported / Invalid / failed evidence is retried after parser upgrades.
- Reparse replaces the ParsedEvent keyed by rawSmsId, never duplicates RawSms,
  keeps user corrections (keyed by rawSmsId) and existing transaction links, and
  runs derived discovery / reconciliation / review refresh once at the end.

### M1.3 — Bank-adapter contract requires real evidence

- `BankSmsAdapterContract.verify` runs against per-adapter
  `BankSmsAdapterContractSamples`; AlJazira samples come from the on-disk fixture
  corpus, the stub adapter carries its own minimal formats.
- Asserted: bank ≠ UNKNOWN; positive senders detect as `adapter.bank`; known-negative
  senders are not claimed; parsed event bank equals `adapter.bank`; financial
  fixtures parse SUCCESS with an amount; non-financial fixtures stay
  `NonFinancial`; unsupported/unknown samples never parse SUCCESS.
- The registry contract requires each sample to be claimed by exactly one adapter
  and to route identically under any registration order.

### M1.1 — Bank routing includes ambiguity

- `BankSmsRegistry` evaluates all adapters; first-adapter-wins is gone.
- `BankRoutingResult.Ambiguous` (more than one `Detected`) is bank-like evidence:
  ingestion persists the RawSms and writes a direct REQUIRED review with reason
  `ambiguous_bank_route`, and neither candidate adapter parses it. Stored reparse
  of such a RawSms (no ParsedEvent, more than one adapter) stays in review.
- Single-bank AlJazira routing is unchanged.

### M1.2 — Router owns bank detection

- Detection runs once, at `BankSmsRegistry.route`. `AlJaziraMessageParser` no longer
  holds a detector, `BankMessageParser.canHandle` is removed, and
  `AlJaziraParsingPipeline` parses without a second sender check.
- Stored reparse that selects the adapter from the stored ParsedEvent bank is no
  longer silently rejected by a parser-level sender check.
- `ProcessRawSmsUseCase` treats an event whose bank differs from the routed adapter
  as a processing error (direct review), so parser and router cannot disagree.
- Sender near-miss coverage moved from parser assertions to routing assertions.

### M2.1 — Comparison-only Arabic normalization

- `core/text/ArabicTextFolding` defines the equivalence (alef/yeh folding, tatweel,
  diacritics, bidi/zero-width marks, colon and Arabic separator variants). It is
  applied only to `comparisonBody`; `originalBody` and `normalizedBody` keep the
  bank's letters, so merchant/counterparty display text is not degraded.
- Every AlJazira classifier keyword, heuristic, and extractor regex is folded the same
  way (`comparisonRegex` / `containsComparison`); display values are sliced from
  `normalizedBody` through `NormalizedSms.normalizedSlice`, which maps offsets across
  dropped characters. `OtpMessageHeuristics` folds its own input.
- Fixture variants (bare alef, ي for ى, tatweel, diacritics, RLM marks, colon variant)
  must parse to the same facts as the canonical fixture.

### M2.2 — Classification is deterministic evidence resolution

- `AlJaziraMessageClassifier` no longer uses an ordered `when` chain. Every
  `AlJaziraClassificationRule` is evaluated and `AlJaziraClassificationResolver`
  keeps the highest `AlJaziraClassificationSpecificity` tier:
  security (OTP) > account notices > balance notice > statement > named products
  (installment, card payment, bill, refund) > money movement (purchase, withdrawal,
  transfer, fee-titled message) > generic (a «رسوم» line outside the title).
- One family in the top tier wins; its evidence lists `outranked:<rule>` for every
  other family that matched. Two families in the top tier → `UNKNOWN` with
  `ambiguous_classification` + `candidate:<rule>` evidence → `REVIEW_REQUIRED`.
  `rank` only breaks ties inside a family (POS over online wording).
- Rule list order carries no precedence; tests shuffle the production rules.
- Every pre-existing fixture, reference body, and test SMS literal classifies exactly
  as before. Collision fixtures (`collision_*`) pin the new behavior: transfer + fee
  line → transfer; fee title + transfer wording, or incoming + outgoing titles → review.

### M2.3 — Validator is the final automatic-use firewall

- `DefaultParsedEventValidator` adds V-010 (positive amount), V-011 (family/direction
  consistency), V-012 (explicit `AutomaticUsePolicy` confidence minimum, 0.8),
  V-013/V-014 (four-digit card/account suffix shape), V-015 (plausible local time,
  injectable `Clock`), V-016/V-017 (conflicting strong facts) on top of V-001…V-009.
- `ParseFinalizer` has one gate: any ERROR finding on a financial family →
  `REVIEW_REQUIRED` with the event kept (the unreachable INVALID branch was removed).
  `ValidationResult` exposes `reviewReasons` and `blockingCodes`.
- Every existing fixture still finalizes as before; fixture tests prove each SUCCESS
  fixture fails safe to review under a stricter confidence policy or an implausible clock.
- Card payments accept OUTGOING or INCOMING because direction is relative to the
  referenced account or card. Instrument presence (card/account) is not required:
  existing SUCCESS parses include instrument-less SMS, so requiring it would change output.

### M3.1 — Capture is separate from processing

- `CaptureBankSmsUseCase` routes, dedupes (including the cross-source near-duplicate
  window) and persists `RawSms`, returning `BankSmsCaptureResult` (`Captured` carries
  the row and its `Matched`/`Ambiguous` route). It never parses or reconciles.
- `ProcessStoredSmsUseCase` owns parse → ParsedEvent → discovery → reconciliation →
  review. `process(rawSms, route)` reuses the capture's route in the same attempt;
  `process(rawSmsId)` loads stored evidence (adapter: stored event bank → sole adapter
  → route) and is safe to retry; `reparseStored` is the backlog entry point.
- `ProcessRawSmsUseCase` remains as a capture-then-process facade (historical scan,
  reprocessing, tests). `LiveSmsIntake` calls the two use cases directly.

### M3.2 — Live processing runs in WorkManager

- `LiveSmsIntake.ingest` captures and schedules by rawSmsId, then returns; the receiver
  no longer owns parse/reconciliation lifetime. `LiveSmsProcessingWorker` is an execution
  adapter over `ProcessStoredSmsUseCase.process(rawSmsId)` with no parsing or financial rules.
- Work data holds only the rawSmsId. Live ids embed sender and body hash (see
  `AndroidSmsMapper`), the same app-private evidence identity already stored in `raw_sms`.
- Unique work + `KEEP` and idempotent stored processing make duplicate broadcasts and
  retries safe. Retries are bounded (`MAX_ATTEMPTS`); `processing_error` reviews keep
  exhausted evidence visible and eligible for reparse.
- A startup sweep reschedules evidence with neither a ParsedEvent nor a review row, so a
  process death between capture and enqueue does not lose a recognized-bank SMS. The
  sweep runs after startup maintenance and never blocks it.
- No expedited work: on API < 31 that requires foreground-service info, and plain
  one-time work without constraints already runs promptly.

### M3.3 — Historical import uses one derived pass per batch

- `HistoricalSmsBatchProcessor.Batch.ingest` captures and calls
  `ProcessStoredSmsUseCase.parseAndStore` (ParsedEvent or direct review, no derived work).
  `Batch.finish` observes ownership for the stored events in arrival order, runs one
  `TransactionReconciliationService.reconcileBatchDetailed` pass, and applies its report to
  the review queue once. This replaces per-SMS reconciliation plus the two full passes that
  previously ran at scan end. Live processing is unchanged (per message).
- `reconcileBatchDetailed` visits stored events in RawSms arrival order (not ParsedEvent id
  order), so new transaction ids equal those a message-by-message import assigns.
- Characterization: the full AlJazira fixture corpus imported as one inbox (empty and
  pre-owned registries) yields identical RawSms, ParsedEvents, transactions, reviews, and
  registry entries to the per-message flow.
- Deliberate difference: transfer legs between owned accounts that are bridged only by a
  shared reference (neither SMS names the other account) used to become two external
  transfers, because each leg was posted before its counterpart existed. The batch pass sees
  both legs and pairs them into one SELF_TRANSFER — the order-independent result
  `reconcileStoredEvents` already gives for the same evidence.
- A scan that fails mid-way keeps counters/evidence and still finishes the batch for events
  it stored; a cancelled scan leaves stored evidence for the next scan or reprocess pass.

### M4.1 — Dashboard evidence is scoped

- `DashboardService.loadProjection` no longer calls `ParsedEventRepository.listAll()` or
  loads RawSms one by one. `DashboardEvidenceScope` loads linked evidence for the
  transaction sets a projection displays (batched: transaction ids → RawSms ids →
  ParsedEventRecords → RawSms) and extends it per stage (commitment sources, card
  statement window, statement-settling payments) only for transactions not yet covered.
- History rules that do not depend on a displayed transaction get one explicit fact query
  each (statements, newest credit row per card, latest available balance before the period
  end, financing installments, merchant FX rates, first debit/source-account row per
  registry card). They are bounded by kind rather than by a received-at window: the
  statement, loan, and FX rules take "latest before the period end" with no lower bound,
  so a time window would change outputs for long histories.
- Fact queries may over-fetch but never under-fetch; evidence is ordered by event id, so
  every projection equals the former whole-history load. Characterization compares both
  loads over the AlJazira fixture corpus and a 23-month synthetic Room ledger, and each
  fact query/extension is proven necessary by that comparison.
- Out-of-period credit-card payments are read with
  `listByTypesOccurredSince(CREDIT_CARD_PAYMENT, earliest due update)` instead of every
  payment ever recorded.

### M4.2 — Dashboard projection is read-only

- `DashboardProjectionBuilder` no longer persists exchange rates. `AppliedExchangeRateSyncer`
  is now pure (`applyInMemory`): the displayed period transactions carry the resolved rate
  in memory, exactly as they did after the old write, so totals and displayed rates are
  unchanged. The card-window write, whose in-memory result was already discarded, is gone.
- `ExchangeRateEnrichmentWorkflow` owns persistence: it lists foreign transactions with no
  persisted rate (`listAwaitingAppliedExchangeRate`), loads their linked evidence plus the
  merchant-rate facts, resolves with the shared `TransactionSarEquivalentResolver`, and
  writes only after every resolution succeeded. It is serialized by a mutex, never
  overwrites a persisted rate, and is idempotent.
- Callers: `ProcessStoredSmsUseCase.process(rawSmsId)` (live worker path),
  `HistoricalSmsBatchProcessor.Batch.finish`, the bulk-reparse derived refresh, and startup
  maintenance (background, after the pending-SMS sweep). Each call is best-effort; neither
  Compose nor `DashboardViewModel` persists anything.
- Rates freeze when first persisted. Before, that happened on the first dashboard view;
  now it happens at ingestion or maintenance time with the same resolver and evidence, so
  the dashboard shows the same values before and after enrichment (characterized).

### M4.3 — Dashboard projection is composed by read-model concern

- `DashboardProjectionBuilder` loads a `DashboardProjectionContext` once and composes four
  section projections: Analysis, Accounts, Cards, Commitments (`*DashboardProjection`, all in
  `application/dashboard`). Bank hierarchy stays a one-call composition of section outputs.
- Sections own only their extra reads (cards: statement-window transactions; commitments:
  commitments, out-of-period sources, statement-settling payments). The loan registry, read
  twice before, is now read once into the context.
- No rule moved or changed: specialist builders/calculators are called with the same inputs.
  `DashboardProjection` output for 100 projections (fixture corpus and long synthetic ledger,
  with and without market rates, 25 periods) is byte-identical to the pre-split builder.
- Section projections are covered by the read-only architecture rule
  (`PackageDependencyRulesTest.dashboardProjection_isReadOnly`).
