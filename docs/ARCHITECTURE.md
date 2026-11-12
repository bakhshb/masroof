# Production architecture

Masroof has one Android application, one financial database and one bank interpretation engine. Planned structure changes are listed in [the sustainability plan](SUSTAINABILITY_PLAN.md); this document describes the code as it is. The tested engine and advisory features are integrated into the existing app; the parallel Lab application and generated source replica are removed.

## Modules

- `app`: presentation → application workflows → domain/parsing/data/SMS/bank ports and the pure financial core. `AppContainer` is the manual composition root of the only production runtime (see "Wiring" below).
- `bank-engine`: pure Kotlin message-pattern matching, literal-role interpretation and extraction evidence, depending only on `finance-core` value contracts. `MessagePatternSmsAdapter` maps its output through `InterpretationDraftMapper` and the production `ParseFinalizer`.
- `finance-core`: production money/value models, salary and statement periods, bank transaction time, deterministic container/transaction identities, financial classification/impact, transfer matching and assembly, and statement matching. Policies consume supplied facts and return decisions or transactions in memory; there is no ledger, repository, Android dependency or I/O. Existing engine value contracts remain available separately.
- `ml-assist`: exact human-template family memory, cleaned templates, forecasting and recurring-pattern detection. The engine consumes an injected assistance contract rather than depending on ML implementation.
- `ui-kit`: shared theme, typography, fonts, cards and charts. It cannot access financial storage.

Source namespaces use `com.baraa.masroof.engine.bank`, `com.baraa.masroof.finance.value`,
`com.baraa.masroof.ml` and `com.baraa.masroof.analytics`. Application integration
tests use `com.baraa.masroof.integration`. Historical `lab`/`beta` source paths and
source file prefixes have been removed. Existing serialized backup keys and retired
work identifiers keep their original spelling for upgrade compatibility.

## Wiring

`AppContainer` is a thin root that builds one plain feature container per area (`application/container/`). There is no DI library.
Each container takes the objects it needs from the ones built before it, so every shared object (database, access gate, repositories,
completion signal, schedulers, app log, stores) exists once. A dependency that would form a cycle is read lazily through a lambda.

| Order | Container | Owns |
|---|---|---|
| 1 | `StorageContainer` | restore recovery, pattern store, Room database and access gate, repositories, preferences, app log, application scope |
| 2 | `MaintenanceContainer` | startup integrity repair and its outcome, `MaintenanceCompletionSignal` |
| 3 | `UpdateContainer` | release checks, pending update, token, APK installer, the shared HTTP client |
| 4 | `LearningContainer` | `BankInterpretationService` (absent when a test supplies its own bank adapter) |
| 5 | `ReviewContainer` | ownership, reconciliation, review queue, restore, reclassify and ignore |
| 6 | `DashboardContainer` | dashboard reads, layout and notification preferences, registries, commitments, exchange-rate writers |
| 7 | `SmsContainer` | bank routing, capture, processing, historical scan, live intake, reprocessing, statements |
| 8 | `PatternsContainer` | message-pattern workflows and the learning workflows that read stored evidence |
| 9 | `BackupContainer` | backup export and restore |

The root also holds `analytics`, `close()` and the shutdown stage. `appWorkerFactory` creates all seven workers
(`LiveSmsProcessingWorker`, `ExchangeRateEnrichmentWorker`, `HistoricalDerivedRecoveryWorker`, `LiveCaptureRecoveryWorker`,
`ModelRefreshWorker`, `UpdateCheckWorker`, `AnalyticsWorker`); no worker reads the application. Unknown classes fall back to WorkManager's
default factory. `DatabaseRestoreRecovery.recover` runs first, inside `StorageContainer`, before the pattern store and Room are created.
ViewModel factories take the feature containers they read from. Test seams: `bankAdapterOverride`, `smsDataSourceOverride`,
`databaseOpenHelperFactory`, `restartAfterRestore`.

## Message-pattern ownership

Keep the existing five modules. Organize by responsibility; a filename containing
`Pattern` does not mean its approval, storage or UI belongs in the engine.

| Location (under `com.baraa.masroof`) | Owns |
| --- | --- |
| `bank-engine: engine.bank.patterns` | Immutable definitions, compilation, matching, extracted facts and provenance |
| `bank-engine: engine.bank.interpretation` | Shared role, text, money, date and safety primitives |
| `app: bank` | Bank routing/detection and mapping engine output into validated parser drafts |
| `app: application.patterns` | Teaching, revisions, financial approval, pending preview/application and display models |
| `app: application.interpretation` | Configured adapter snapshots, family assistance and automatic-write eligibility |
| `app: application.ingestion` | Durable processing, protected evidence and retry/resume orchestration |
| `app: data.patterns` | Strict JSON codecs, immutable revision persistence and atomic file replacement |
| `app: application.backup` | Pattern capture, restore and rollback integration |
| `app: presentation.patterns` | Render application models and collect user input |
| `finance-core` | Financial classification, matching and assembly from supplied facts |

`StorageContainer` loads `app/src/main/assets/bank-patterns/*.patterns.json`, installs
missing bundled definitions before admitting work, and wires the store into
`ConfiguredPatternAdapters`. Each routing attempt receives a complete immutable
snapshot. `BankSmsRegistry` evaluates every adapter; only a resolved bank reaches
`PatternFactInterpreter`. Banks with bundled role rules (AlJazira, Alinma, Barq,
Emirates NBD, SAB, SNB) use the shared role interpreter within that snapshot; STC's
eight bundled templates require explicit financial approval.

A free-text `account_reference` slot may be empty while its literal label remains
required (for example, STC SARIE's blank `Account` line). Empty values produce no
reference evidence. Approval may use the selected account as a definition constant;
an exact account-reference binding still requires its observed value. Amounts,
dates, other text slots and numeric account slots remain required.

Settings groups bank operations under **Banks**. Configured banks remain visible
before any account is discovered. Names and senders come from the existing pattern
catalog; edits preserve bank-qualified identities and immutable pattern revisions.
A bank page owns accounts, cards, loans, teaching, SMS import and CSV comparison.
Backup/restore and stored-data maintenance remain separate; correction insights
remain app-wide. Legacy navigation keys remain readable.

First-run setup uses the same bank choices, bank editor, inclusive receipt-date
picker, manual registry sheets and card setup as Settings. It requires an explicit
bank selection and calls the same `BankSmsImportWorkflow`; onboarding does not run a
second discovery/reconciliation pass after the batch. Ownership review is scoped
to selected banks, and owned cards need a configured type before setup completes.
Unconfigured bank patterns remain visible and can be taught/approved after setup.
The optional `bank_setup_draft` preference preserves bank identities and both dates
across restarts and backup/restore rollback. Existing preference names and completed
setups remain compatible; they add nothing to the financial schema.

Settings and dashboard historical-import actions open the common bank/date picker. `BankSmsImportWorkflow`
converts inclusive local calendar dates to an exclusive end instant and runs one
`HistoricalSmsScanner` batch for the selected bank set. Routing still owns sender
detection; collisions remain reviewable. Selected-bank and upper-bounded scans never
certify whole-inbox analysis coverage. Permission grants and dashboard refreshes do
not import unselected history. Import completion uses the existing maintenance signal
to refresh open screens. CSV comparison restricts known banks and exact owned account
identities to the selected bank, and stays read-only.

Discovery still creates unknown ownership candidates from parsed user-side references.
Manual account/card entries require explicit ownership confirmation; card creation and
configuration are atomic. Entries alone create neither balances nor transactions.
Pattern approval can select an explicitly referenced owned, configured card with an
optional account binding; it never invents a funding account. Approval rechecks ownership
and the configuration generation before saving. A built-in role rule keeps its family and
readings; approval may only add an owned account link (or a card link for purchases, refunds
and card payments). A link fills only a role the message leaves empty, never replaces a value
the message names, and never funds a purchase, refund or card-named movement from an account.
Editing a built-in teaches a new pattern and turns the built-in off once the replacement is approved. The financial schema and the existing
ingestion, validation and posting guards are unchanged.

The bank catalog groups definitions by operation and opens each bank-qualified
pattern separately. Teaching collects the
message type, explicit field selections and a reviewed preview; saving that preview
does not post a transaction. Explanations use the shared contextual guidance control.
From review, the original sender/body remain read-only and the review id stays in
memory. Explicit **Apply to this message** scopes the existing pending workflow to
that review, retaining its bank, correction, ownership and posted-evidence guards.
Returning reloads review facts without reconciliation. Type correction remains a
type-only draft applied on Save; recorded transactions retain their existing
reclassification path and preselected current type.

A match is never posting authority. The mapper/finalizer validates facts;
application guards check the current fingerprint, revision, ownership and human
decisions inside the financial transaction before existing financial policies run.
Protected evidence resumes without parsing again. Approval and pending application
are separate actions and reuse existing ownership/intake/reconciliation services.

Financial pattern editing reuses teaching with the same bank-qualified pattern id
and a new immutable revision. Matching example spans prefill the editor, including
ignored variable slots. Unselected money, date and number candidates become ignored
slots automatically when the template compiler accepts them; otherwise the preview
lists them as fixed text. Financial approval switches the pattern on. Saving an edit clears financial approval and retains the enabled
state; leaving the editor discards the draft. Supported teaching operation types
use this editor; other built-in families retain their existing enable/revision controls.

Teaching keeps one short question and one primary answer/action fixed while the
message content scrolls. The app-bar Back action returns to the previous field,
retaining draft selections; optional fields have a secondary skip action. A
read-only preview shortcut stays in the app bar once the amount is bound. Wide
layouts use a bounded form with message and value columns; geometry lives in
`MasroofTeachingTokens`. Example testing and revision history use separate pages.
Pattern details separate message fields from financial approval using the shared
settings navigation cards. The full message template is always expanded and uses
localized field labels; the catalog eye action opens the same read-only template
in `MasroofBottomSheet` without editing or writing a revision. Returning from a
review-sourced pattern uses the app-bar Back action, including after approval or
application. Deletion has its own full-width bordered button; the enabled-state
and exact-name confirmation guards remain enforced. Account-creation drafts cancel
without changing the prior selection.

Permanent deletion requires a disabled latest revision and an exact pasted name
confirmation, with a copy-name action. The store checks the disabled state and
generation again, atomically removes every revision and retains only the deleted id
to suppress bundled reinstallation. Pattern configuration schema 4 persists those
bank-qualified deletion markers through backup/restore; schemas 1–3 remain readable
and retain their existing encoding until a deletion is saved. Deletion changes no
RawSms, parsed evidence, corrections or posted financial rows. The financial Room schema
is unchanged; deleted patterns cannot be recovered through revision restore.

Keep integration fixtures on the bundled production definitions. Kotlin rule
profiles live only in `bank-engine/src/testFixtures/`, shared through Gradle's built-in test-fixtures support. The offline CLI is an explicit test tool. Neither
is another production definition source. Preserve the frozen AlJazira baselines.
[Pattern facts](PATTERN_FACT_CONTRACT.md) describes the current write contract;
[AlJazira compatibility](ALJAZIRA_SHARED_PATTERNS.md) records the frozen-reference
checks and remaining signed-upgrade/restore release gates. The production ownership
table above supersedes the completed migration plans and prototype execution logs.

## Bank interpretation

`MessagePatternSmsAdapter` is the one production adapter; `ConfiguredPatternAdapters`
gives it immutable bank-scoped snapshots from the pattern store. Every adapter detects
its bank with `SenderBankDetector`, and `SenderIdentity` (bank-engine) is the one rule
for when two sender names are the same sender: case, spacing, punctuation, Arabic letter
forms and digit scripts do not matter, and a promotional `-AD` suffix still names the
sender. Only an equal sender is detected. A sender containing a long configured sender
stem, or a body naming the bank, is only Suspected: stored and reviewed, never parsed.

`PatternFactInterpreter` extracts facts with evidence and blockers, delegating role
rules to the shared `RuleInterpreter`. Kotlin `RuleLibrary` and `AlJaziraRuleProfile`
are test vocabulary; production does not load them. Local family assistance comes from
`ml-assist`; rules and security checks stay authoritative. `InterpretationDraftMapper`
keeps blockers and sends drafts through the validator and `ParseFinalizer`, the only
source of successful financial parse results. A parse result is not a posted
transaction; missing, contradictory or unexecuted facts stay reviewable, and
bank-qualified references never infer ownership. See
[pattern facts and write gate](PATTERN_FACT_CONTRACT.md).

## Financial policy ownership

The extracted sources keep their original `com.baraa.masroof.core.money` and
`com.baraa.masroof.domain.*` packages and persisted enum values.
Classification consumes `ClassificationEvidence` with resolved container facts;
production and policy tests use the same input contract.
Only the value models needed by those policies move. The two existing Money
contracts are not unified. `bank-engine` may still reference only
`com.baraa.masroof.finance.value`; depending on the core module does not give
it authority to classify, match or assemble financial transactions.

Ownership lookup/discovery/confirmation, repository ports, reconciliation,
human correction, durable retry and `ReconciliationCompletionPolicy` stay in
`app`. Assembly returns an in-memory outcome; the existing application workflow
still checks current evidence and human decisions inside the financial write
transaction. Dashboard builders/calculators and evidence scoping also stay in
`app`. The statement match policy and direction value live in core; canonical
CSV parsing, its stream interface and reconciliation workflow stay in app.

`FinancialTransaction.fromSingleEvent` shares the construction of a transaction
from one parsed event between automatic assembly and manual review. Callers
still authorize the operation and choose its type, endpoints and timing; the
factory does not classify, resolve ownership or write to storage. Manual and
automatic endpoint precedence remain separate.

This extraction preserves the financial schema and the existing write paths. It introduces
no backfill, history reparse, new database, worker, backup format or UI behavior.
Policy tests run directly on JVM in `finance-core`; app integration tests cover
the unchanged persistence, orchestration and dashboard paths. Architecture checks
scan both source roots, require the moved policies to exist in core, reject
app-side copies and reject fully qualified calls that bypass module import rules.

## Processing and authority

Live SMS is captured durably before WorkManager receives a raw SMS id. Historical intake parses a batch before one discovery/reconciliation/review pass. Validation, confirmed ownership and manual decisions retain financial authority. Informational/OTP messages never post. Reconciliation failures retain durable retries.

The engine uses rules first. Eligible local family inference never supplies an amount, ownership or approval. Explicit corrections feed local learning. Rule/model refreshes target unresolved, unposted evidence without a manual decision. Previously posted history is preserved.

Message learning is "remember my decision", per bank, for every configured bank. Each bank's exact-template memory is built only from that bank's human decisions; there is no statistical message model. A remembered "not financial" decision settles a later message with the same template that the rules could not identify, in any bank, and `InformationalMessagePolicy` keeps it out of review. A remembered financial family is used only where the bank's role rules ask for one; it never supplies money, ownership or posting. Conflicting decisions for one template propose nothing. Banks with role rules re-read their pending messages when the rules or their decisions change; banks without role rules refresh only the pending messages a learning change would change, so taught patterns are still applied explicitly. Banks the user adds learn the same way.

## Data and recovery

`masroof.db` opens schemas from the frozen v18 baseline (`MasroofDatabase.BASELINE_VERSION`, fourteen tables, unchanged identity hash) up to the current `MasroofDatabase.VERSION` (19) through `MasroofDatabase.MIGRATIONS`, one additive, tested Room migration per step (see AGENTS.md). `MasroofDatabase.builder` is the only builder for the live file. Older, unknown and newer schemas are rejected by Room and the file stays byte-identical (`MasroofMigrationOpenPathTest`, `MigrationOpenPathDeviceTest`): Room compares the identity hash of a file at the current version and validates every table after a migration, and `MIGRATION_18_19` first refuses a v18 file whose identity hash is not the known v18 hash (inside the upgrade transaction, so the file stays untouched). Never a destructive fallback. Automatic schema-fact backfill is removed; already queued backfill work completes without parsing or writing. Existing preference files, Keystore alias, app id and worker names remain stable. `learning.db` stores labels/provenance; `analytics.db` stores advisory results and preferences. Old Lab files are neither imported nor deleted automatically.

### User decisions (`user_decision`, schema v19)

`MIGRATION_18_19` adds one append-only table, `user_decision`, and nothing else (CREATE TABLE and indices only; no backfill, plan piece 2.2 is an owner gate). Every explicit user decision is appended there in the same database transaction as the existing write it describes; the existing stores (`review_item`, `user_correction`, the `user_selected_bank:` reason prefix, `financial_transaction`) are written exactly as before. Nothing reads the table yet: plan piece 2.3 moves the readers onto it, so each row carries what 2.3 needs without `financial_transaction.type` or reason prefixes. `UserDecisionDao` only inserts and lists; it has no UPDATE or DELETE. A later row for the same subject supersedes an earlier one by `sequence`.

| Column | Meaning |
|---|---|
| `sequence` | `INTEGER PRIMARY KEY AUTOINCREMENT`: the append order, never reused. The latest decision for a subject is the one with the highest `sequence`, not the latest clock time. |
| `rawSmsId` | The subject: the raw SMS the decision is about (the outgoing leg for a pair). Raw SMS ids are stable; transaction ids are not, because reconciliation may recreate a row. Foreign key to `raw_sms` (`RESTRICT`/`CASCADE` like `user_correction`). |
| `pairedRawSmsId` | The incoming leg of a self-transfer pair, else null. Same foreign key. |
| `kind` | A `UserDecisionKind` name (below). |
| `decidedAtEpochMillis` | The injected clock's instant at the decision (the same instant the existing write stores). |
| `transactionType` | The `FinancialTransactionType` the user chose (or that the chosen action posts), for `FINANCIAL_TYPE`, `EXTERNAL_TRANSFER`, `SELF_TRANSFER_PAIR`, `RECLASSIFY`. |
| `sourceContainerId`, `destinationContainerId` | The endpoints the decision posted with, for the same kinds. They are derived from evidence and ownership at decision time, so 2.3 can reproduce the user's row without reading it back. |
| `occurredAtEpochMillis`, `occurredAtZone` | The effective occurred-at instant (`TransactionTiming.effectiveOccurredAt`) and zone exactly as posted on the `financial_transaction` row, for the same kinds. `RESTORE` is appended before reconciliation posts, so it has none; a type chosen on restore adds a `RECLASSIFY` row that has them. The amount is not copied: it comes from the evidence or a correction. |
| `messageFamily`, `amountDecimal`, `amountCurrency`, `merchant`, `counterparty` | A `CORRECTION`'s fields, encoded like `user_correction` (family name, `BigDecimal.toPlainString()`, currency name). The user's own text, never SMS body text. |
| `bankId` | The bank of a `BANK_SELECTION`. |

Unused columns are null. Indices: `(rawSmsId, sequence)` for "latest decision for this SMS", `(pairedRawSmsId)` for the second leg of a pair.

| Kind | Writer and call site | Existing write it joins |
|---|---|---|
| `FINANCIAL_TYPE` | `ReviewWorkflowService.resolveAsFinancialType` → `RoomManualReviewResolutionRepository.persistSingleResolution` | posted row + review `USER_FINANCIAL_TYPE` |
| `EXTERNAL_TRANSFER` | `ReviewWorkflowService.resolveTransferAsExternal` → `RoomManualReviewResolutionRepository.persistSingleResolution` | posted row + review `USER_EXTERNAL_TRANSFER` |
| `SELF_TRANSFER_PAIR` | `ReviewWorkflowService.resolveSelfTransferPair` → `RoomManualReviewResolutionRepository.persistPairResolution` | posted row + both reviews `USER_SELF_TRANSFER_PAIR` |
| `NON_FINANCIAL` | `ReviewWorkflowService.resolveAsNonFinancial` | review `USER_NON_FINANCIAL` |
| `CORRECTION` | `ReviewWorkflowService.applyCorrection` | `user_correction` row (the later `USER_CORRECTION` review closure is its consequence, not a second decision) |
| `RECLASSIFY` | `TransactionReclassificationService.reclassify` → `RoomTransactionReclassificationRepository.updateWithDecision` | row type/endpoints + review `USER_FINANCIAL_TYPE` |
| `IGNORE` | `TransactionIgnoreService` → `RoomTransactionIgnoreRepository.ignoreSingle` | row deleted + review `USER_NON_FINANCIAL` |
| `RESTORE` | `TransactionRestoreService.restore` | review `USER_FINANCIAL_TYPE` (a chosen type adds its own `RECLASSIFY` row) |
| `RESTORE_REVERTED` | `TransactionRestoreService.restore` when nothing could post and the restore is undone | review back to `USER_NON_FINANCIAL` |
| `BANK_SELECTION` | `ExplicitBankSelectionWorkflow.selectAndReparse` | review reasons with the `user_selected_bank:` prefix |

Application services join through `UserDecisionRepository.recordWith(decision, before) { write }`, which reads the current row, runs the write and appends in one Room transaction under the access gate, and appends only when the write returns a result that differs from the row before it; the Room repositories append through `UserDecisionDao` inside their own transaction. An idempotent repeat (a double submit, the same bank chosen again) changes nothing and appends nothing. A failure at the append rolls the existing write back too.

The learning store is schema 2 and the analytics store is schema 4. Posted type edits atomically update the financial transaction and its latest `USER_FINANCIAL_TYPE` review decision, and append a `RECLASSIFY` row to `user_decision`. Message learning rebuilds from durable decisions before enabling personal assistance; no derived message-model cache is written or loaded. Exact template suppressions include bank and feature identity and revoke human-template assistance. Financial `EXPENSE`/`INCOME`, ownership decisions and field-only edits do not invent family labels. Unsupported or ambiguous decisions have an explicit feedback disposition.

`USER_EXTERNAL_TRANSFER` now teaches an incoming/outgoing family from the external type committed with the resolved review, including compatible retained decisions. The UI sends the direction explicitly displayed by its action. A changed direction is rejected before assembly; a changed effective evidence snapshot is rejected inside the financial write transaction. The refreshed detail explains the rejection. Missing decision evidence remains pending; later contradictory edits supersede the label and can suppress the template. Broad financial types still do not count as learned family corrections.

Automatic reconciliation compares its assembled evidence with current effective evidence and human decisions inside the financial write transaction. A stale model version or a concurrent correction cannot post an older interpretation. Feedback failures remain retryable without undoing the financial correction. Model refresh work synchronizes feedback before selecting a version and only refreshes eligible unposted evidence. Each refreshed parse commits an existing `HISTORICAL_BATCH` processing-retry marker in the same financial transaction. One scoped derived-recovery pass clears the marker only after reconciliation and review completion; retries also run that pass when all interpretations are already current. The worker holds a database access lease across the full operation, and container shutdown drains admitted operations before closing the financial or learning database.

Template memory keeps the same cleaned template identity, so learned corrections survive. Its version is `family-template-v1` plus a hash of the current decisions; a changed version marks older inferences stale and refreshes only unposted, undecided evidence.

Startup blocks financial screens while required maintenance is incomplete. Dashboards read persisted scoped evidence without parsing SMS or writing FX. Analysis reads approved financial inputs and writes only advisory results; a suggested commitment needs explicit confirmation through the existing commitment workflow.

Navigation state, including analytics notification targets and return origins,
lives in `presentation/navigation`. Workers and application workflows do not
own screen navigation. Settings uses the shared card setup draft and one
registry-write/reload path; configuring a card does not reparse or reconcile
financial history. Renaming an account or card also uses that metadata-only path.
Manual reparse reconciles once, synchronizes merchant evidence, then completes
review updates using that same report. Settings reloads registries after a completed
reparse or rescan; it does not start another financial pass. A failed report remains
incomplete and durably retryable even if a second pass could succeed.

Encrypted backups retain the existing envelope. A restore accepts only a known financial (version, identity hash) pair from v18 up to the current version (`MasroofDatabase.IDENTITY_HASHES`, checked against the exported schemas); anything else is an invalid package and the live database is not touched. An older backup migrates on its staged copy through `MasroofDatabase.builder` with the preserving open helper, while the live database is still open, before the restore journal is written and the live file is replaced; a failed migration leaves the live files, preferences and journal unchanged (`BackupOlderSchemaImportTest`, `BackupMigrationDeviceTest`). Export always writes the current version and hash. Optional analysis is included in the durable restore journal for commit/rollback. Restoring an older backup clears stale analysis and rebuilds available results. Learning state is invalidated and reconstructed from retained human decisions.

See [AGENTS.md](../AGENTS.md) for enforced boundaries and [ANALYTICS.md](ANALYTICS.md) for model and alert policy.

### Closing SQLite-backed services

Application shutdown stops producers and drains admitted startup/database work before closing Room. Never cancel a first-open Room continuation and assume SQLite has stopped: its connection callback can still own the open-helper monitor while invalidation setup requests the Room close read lock. Taking Room's close write lock at that point deadlocks. Startup, learning/audit and analytics drain normally; the database access gate also drains independent WorkManager leases. `StartupQueryShutdownTest` controls that native first-open boundary.

## Recurring suggestions and forecast replay

Recurring suggestions come from rules only; there is no recurrence model. Analytics
schema 4 stores recurring feedback, including an intent recorded before the financial
save; a later pass completes it only when the saved commitment's source and cadence
agree. Feedback failure never undoes a saved commitment. Legacy rejection stays a
dismissal; explicit "not recurring" decisions stay until undone. Archive 4 reads
archives 1-3 and financial schemas 18 and later.

The forecast baseline is a capped weekday mean (stored key `WEEKDAY_MEDIAN` for
compatibility); the personal Huber model must beat it to be used. `ForecastReplay`
runs the forecaster on a strict historical prefix; replay results never become
published forecast history. See [ANALYTICS.md](ANALYTICS.md).

## Transaction list projection and confirmation state

`TransactionListWorkflow` accepts immutable dashboard row facts and a `TransactionListQuery`.
Its application calculator owns filtering, bank-qualified ownership lookup and currency-scoped
totals; it never reads or writes storage. The presentation mapper maps selected row indices back
to existing UI rows, preserving ordering and duplicate display IDs. `DashboardViewModel` reapplies
the query after refresh and clears the projection when a new period starts loading. Compose
renders the prepared projection and sends filter changes to the workflow through the ViewModel.

Settings uses one `StopTrackingTarget` (`Card`, `Account` or `Loan`) rather than three independently
nullable confirmation targets. Existing card/account/loan displays derive from that one target;
write actions, draft/discard behavior and financial decisions are unchanged.

## Dashboard chart scopes and entity summaries

`application/dashboard/DashboardSpendingTrendsBuilder` computes every display scope from the
persisted dashboard context; Compose does not sum money. These scopes affect charts, not the
ledger or the deduplicated overall Home expense scope.

- Overall expenses: expenses, bills, fees, loan repayments, external outgoing transfers and
  withdrawals, less refunds. Self-transfers and card settlements are excluded to avoid double
  counting purchases.
- Home has one chart with two scopes. Daily purchases (default): attributed credit/Mada
  purchases, cash withdrawals and external outgoing transfers, less card-attributed refunds.
  Bills, repayments and standalone fees appear only in All daily expenses.
- Credit charts: purchases, bills and ordinary fees less refunds. Mada charts: only confirmed
  card purchases, cash withdrawals and refunds; an account link alone never assigns an
  unidentified withdrawal to a card.
- Account charts (`AccountDailySpendingTrendsBuilder`) use confirmed single-account source
  attribution for purchases, bills, loan installments, card settlements, external outgoing
  transfers, withdrawals and fees, less verified account refunds, through the same
  registry-linked debit scope as account summaries (including Google Pay purchases without an
  account SMS field). Credit charges/refunds are excluded even when their evidence mentions an
  account. The aggregate excludes self-transfers; per-account charts include only the outgoing
  self-transfer leg. Inflows never raise the expense line. Links stay qualified by bank and
  account identity.
- FX needs a resolved local equivalent; unresolved foreign amounts and unknown sources stay out.
- The daily mean uses every elapsed date in the selected period, zero days included; completed
  periods use their full duration and future periods have no mean.

Entity summaries share one card shell with type-specific facts:

- Account: calculated inflow/outflow/net movement.
- Credit: recorded available balance, facility amount due and due date from the same
  statement; supplementary cards share the facility due, counted once in totals. A missing
  latest date shows as unavailable, never filled from an older statement.
- Debit: linked account and net purchases/refunds, with no credit due.
- Loan: recorded remaining balance, latest installment and its recorded date.

Summary pages distinguish a failed load from an empty portfolio: a failed refresh keeps the
previous same-period data with an error notice, and a failed initial load never shows an
empty state. Merchant analysis needs at least two eligible purchases.
