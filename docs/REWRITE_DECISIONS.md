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
