# Masroof agent notes

Android app that turns Saudi bank SMS into a private ledger and dashboard. Arabic first (RTL), English second.
Reference: `docs/ARCHITECTURE.md` (modules), `docs/ANALYTICS.md` (analysis), `docs/DESIGN_SYSTEM.md` (UI tokens and components).
This file wins when older records disagree.

## Priorities
When rules pull against each other, the earlier one wins:
1. Correct money and no lost data: the financial, pipeline and completion rules below.
2. Never break: schema, identities, privacy.
3. Layer boundaries and tests.
4. A working, accessible, bilingual screen.
5. Visual consistency with the design system.

Design serves the feature. A design rule never blocks a correctness fix, delays a feature or justifies rework of a screen that works; if one gets in the way, ship the fix and report the conflict.

## Workflow
The orchestrator owns the project (owner, 2026-11-13): it reads the code, decides the design and builds the change itself by default. Run this for each change (a feature, a fix):
1. Plan: what changes and the tests that prove it is done. Any change to numbers or behavior the owner sees is asked before implementing, and so is any schema or backup-format change. For a new screen, describe its layout in the plan using existing components; make a design mock-up only when the owner asks for one.
2. Implement with its tests, and run them.
3. Simplify the code and its tests once, and rerun the tests.
4. Review for bugs, fix, rerun the tests; repeat until the review finds no real bug. Owner decisions are reported, not looped on.

Steps 3 and 4 use `/simplify` and `/code-review` for a large or high-risk change; for a small one, simplify and review directly.

Delivery: each change is built in its own git worktree from the latest `main` and goes to `main` as one pull request (no rebases). It deletes the code, tests, imports, fixtures and string resources it makes obsolete, and updates `AGENTS.md`, `CLAUDE.md` and `docs/` when a rule or module changes. At the end of the PR: one review of the whole PR, then CI green including device tests; the orchestrator then merges it and reports done.

### Agents
Agents are used only where splitting the work really helps, and their cost counts. Small and medium changes, review fixes and CI fixes are never handed to an agent.
- Implementer (`.claude/agents/implementer.md`, Sonnet by default; pass Opus for intricate or high-risk work): a large, clearly described task, or independent parts in parallel.
- Reviewer (`.claude/agents/reviewer.md`, Opus): an independent review of a high-risk change only (money, schema, backup and restore, concurrency). The orchestrator reviews everything else itself.
- A read-only search agent: a broad read where only the conclusion is needed.

The agent definitions describe the role; the task brief names the criteria.

No remote pattern download. Wiring stays manual; no DI library unless the owner asks.

## Never break
- Database `masroof.db` (module `data`, schema exported to `data/schemas`; `app` applies no Room/KSP) is schema version 1, as are `learning.db` and `analytics.db`, and the backup format (owner, 2026-11-13: the app is still in development and personal use, so the numbering restarted, the upgrade path from the earlier v18/v22 was deleted and the owner's data was converted once outside the app). Version 1 is frozen since the owner's converted file was handed over (2026-11-13): the schema changes only through additive, tested Room migrations (1→2, ...): a migration test from the committed version 1 fixture, a device upgrade test, and version 1 backup archives still import (a backup of an earlier known version migrates on its staged copy before it replaces the live file). Any other version or identity hash, in a file or a backup, is rejected untouched (`MasroofDatabase.IDENTITY_HASH`, `BackupSchema`). Never a destructive fallback. A schema change needs an explicit design and device verification; no backfill without owner approval.
- `learning.db` and `analytics.db` hold derived data and open through `PreservingOpenHelperFactory`: the framework never deletes a corrupt file. `LearningStore` logs a corrupt cache and invalidates it explicitly.
- Keep the application id, preference names, Keystore alias, worker identities and historical persisted keys.
- The release build shrinks with R8 but keeps names (`-dontobfuscate`): stored worker class names and logged exception names depend on them. A change to R8 rules needs the manual release smoke in `docs/CI.md`.
- No Lab UI, replica app or parallel intake; no `lab`/`beta` source paths. Engine/model refreshes never reparse posted evidence or override human decisions.
- Never log SMS bodies, account numbers or secrets. Logs carry stage, failure count, masked id and retry state.
- PRs target `main`. Full tests run in PR/main CI only; never add `testDebugUnitTest` to `release.yml`.

## Layers
- `presentation` → `application` → `domain` / `parsing` / `data` / `sms` / `bank`; dependencies point inward. `PackageDependencyRulesTest` enforces it: fix the import, never add an exception. The Gradle module graph (the `project(...)` dependencies) is what forbids a module from using another's packages (a pure module cannot use `android.*` or `:app`, `:ui-kit` sees no project, no cycles); source-reading tests cover what Gradle cannot: packages inside one module, which visible packages a module may use, and the pinned graph in `ModuleBoundaryTest`.
- ViewModels call `application` workflows/services only. Presentation never imports `domain.ids|rules|repository|ownership|assembly|matching` or `domain.period`.
- `finance-core` owns `core.money`, value models, period/time policies, identities, classification/impact, transfer and statement matching (packages kept as `com.baraa.masroof.core.money` / `domain.*`). It gets facts and returns decisions; ownership, reconciliation writes and CSV stay in `app`; repository ports and their Room implementations live in `:data`, dashboard aggregation in `dashboard-core`. Do not copy these into `app`. Both Money contracts stay distinct.
- `application` constructors take every service, repository, workflow and scheduler non-null; no `X? = null` collaborator. Tests pass a `NoOp*`/`Empty*` double from `testsupport/` (`NoOptionalCollaboratorsTest` lists the few exceptions; add none).
- `data` (Android library: Room, DAOs, `Room*Repository`, preference stores and the ports they implement) depends on `finance-core` and `ml-assist` only, never on `app`. Add a table, DAO or repository there, not in `app`.
- `bank-engine` may import only `finance.value` contracts. Pure modules use `engine.bank`, `finance.value`, `ml`, `analytics`, `ledger`, `application.dashboard` (`dashboard-core`); app integration tests live under `integration`.
- `ledger` (depends only on `finance-core`) holds `LedgerProjector`, the pure derived ledger. It is the reconciliation writer: `TransactionReconciliationService` loads the scope (`LedgerInputLoader`), projects it and writes only the differences in one database transaction (`LedgerProjectionWriter`); there is no other reconciliation path. Its keep rule (owner, 2026-11-12) carries a decided stored row, or one it no longer derives, verbatim; "Rules of the derived ledger" and "Ledger writer" in `docs/ARCHITECTURE.md` list its rules, never changed silently in the projector.

## SMS pipeline
- Live: `IncomingSmsReceiver` → `LiveSmsIntake` → `CaptureBankSmsUseCase` (durable `RawSms`) → `LiveSmsWorkScheduler` → `LiveSmsProcessingWorker` → `ProcessStoredSmsUseCase`. Work input is the rawSmsId only.
- Live `receivedAt` = PDU service-center time when valid (`LiveReceiptTimestamp`, earliest part), else the injected clock.
- Historical: `HistoricalSmsScanner` → `HistoricalSmsBatchProcessor`: parse each row, then one discovery/reconcile/review pass per batch, never per SMS.
- `ProcessRawSmsUseCase` only wraps `reparseAndStore` for bulk maintenance; tests capture-then-process through the `ingest` test helper. No orchestration under `sms/`.

## Bank routing and parsing
- `BankSmsRegistry` evaluates every adapter. `Ambiguous`/`SuspectedBank` are stored and reviewed, never parsed by a guess; never resolve by registration order. Adapters never re-check the sender.
- Sender detection is `SenderBankDetector` for every bank, and `SenderIdentity` (bank-engine) is the one same-sender rule. Only an equal sender is detected; a long sender stem or a body naming the bank is only `Suspected`.
- Reprocessing order: the latest `BANK_SELECTION` decision, then the stored ParsedEvent bank, then a fresh route. `Matched` parses; only a genuine `NotMatched` may use the sole-adapter fallback. The decision is the only record of the choice; the review detail shows it from there.
- Match on `NormalizedSms.comparisonBody` after `ArabicTextFolding`; display with `normalizedSlice(range)`, never `comparisonBody`.
- Bank logic stays in `bank/*`; fill `ParsedEventDetails` at parse time (`cardSmsChannel`, balances, due dates). Dashboard code reads persisted facts only: never re-parses SMS or imports `bank.*`.
- Interpretation lives in `bank-engine` (`PatternFactInterpreter`, `RuleInterpreter`, explicit evidence/blockers). Production definitions: `app/src/main/assets/bank-patterns/`; Kotlin rule profiles only in `bank-engine/src/testFixtures`. Ownership: approval `application/patterns`, protection `application/ingestion`, composition `application/interpretation`, storage `data/patterns` (the `pattern_configuration` row of `masroof.db`), backup `application/backup`; update `docs/ARCHITECTURE.md` before moving any. No parallel runtime.
- Competing families stay UNKNOWN; OTP wins over quoted financial wording; refunds keep refund authority. Add regression tests for role/classification changes. The retired AlJazira parser/classifier/extractor never returns, test copies included.
- Only `ParseFinalizer` emits `ParseResult.Success`, and only with no ERROR from `DefaultParsedEventValidator`. Tighten via validator rules / `AutomaticUsePolicy`; validators never resolve ownership or type.
- `ml-assist` gives family hints, forecasts and recurring detection only: never money, ownership or posting.
- New bank: a role-rule pattern file in `app/src/main/assets/bank-patterns/` (served by `MessagePatternSmsAdapter`), fixtures under `testdata/`, and a `BankSmsAdapterContractCase` in `BankSmsAdapterContractTest` (financial + non-financial samples, positive/negative senders); no sample claimed by two adapters.

## Financial rules
- An SMS is evidence, not a transaction. Same bank never implies a self-transfer; cross-bank can be one; unknown ownership on either side blocks it. Incoming transfers are not automatically income, outgoing not automatically expense; a card bill payment is not an expense; a refund offsets a purchase, not income. Several messages may describe one transaction.
- Posting gate (`TransactionAssembler.isAutomationEligible`): only `ParseStatus.SUCCESS`, an automation-confirming correction (family/amount) or `USER_FINANCIAL_TYPE`. Merchant/counterparty edits never lift it; reconciliation never bypasses it, except that a row rebuilt from a recorded user decision (`LedgerProjector.decidedRow`) posts without it, as the original manual resolution did. `AutomaticInterpretationWriteGuard` is a precondition of every row the writer posts or changes, inside its write transaction: stale pattern/inference evidence or a decision that arrived meanwhile leaves the stored rows as they are and the SMS failed and retryable (a blocked change of only zone, time, detail or id stays as stored and settled).
- Auto-ignore and stale-link cleanup follow the parsed family (`OTP`, `NON_FINANCIAL`, `BALANCE_NOTICE`) via `InformationalMessagePolicy`; never re-read wording. `UNKNOWN` stays reviewable; the one exception is a message without an amount whose exact wording the user already marked not financial (`FamilyAssistBridge.rememberNonFinancial`), which returns to review if that decision is later contradicted.
- Time: `BankTransactionTimePolicy` is the only bank→zone map (AlJazira `Asia/Riyadh`; others keep the stored `occurredAtZone`). `resolveTransferAsExternal`/`resolveAsFinancialType` use `TransactionTiming.effectiveOccurredAt` and persist that zone. Dashboard code never branches on bank time zones.
- FX: `ExchangeRateEnrichmentWorkflow` persists rates; dashboards apply them in memory. A complete `(appliedExchangeRate, exchangeRateSource)` pair is immutable; repair a half pair from one resolution.

## Processing completion
- `ReconciliationCompletionPolicy` is the only reading of a reconciliation report: `failed > 0` or a throw = incomplete, even if rows posted. Pending-match and intentional non-financial outcomes are not failures. A stored ParsedEvent is not a posted row.
- Live failure → `DerivedIncomplete(RECONCILIATION)`, WorkManager retries; on exhaustion keep a `LIVE` retry row plus an error review; startup reschedules.
- Recovery is one fact: `processing_retry` lists the raw SMS ids still to project. A unit that failed was not written; units the same pass did write stay, and rerunning the pass for an id is safe (idempotent), so recovery only reruns it. `ProcessingRecovery` records failures (`markExhausted`: `LIVE` row plus error review; `retryLater`: one `HISTORICAL_BATCH` set, then enqueues the one `HistoricalDerivedRecoveryWorker`, which startup also enqueues); parse paths write `HISTORICAL_BATCH` in their parse transaction. The stored `mode` says who reruns the pass (`LIVE`: per-message worker, `HISTORICAL_BATCH`: the one batch pass) and stays as stored.
- Historical batch with failures stays `Incomplete`, writes one atomic `HISTORICAL_BATCH` set and clears it only after a later pass with `failed == 0` and a successful review update.
- Every explicit user decision is also appended to `user_decision` (append-only, never updated or deleted) in the same database transaction as its existing write.
- Recurring-payment answers are `RECURRING` rows of `user_decision` through `RecurringAnswerStore` only: keyed by series (`subjectKey`, no `rawSmsId`), the latest row of a series is its answer, an undo appends `UNDONE`, a promotion appends `CONFIRMED`. Every other row names a raw SMS (`UserDecisionDao.insert` enforces both), and no SMS decision read sees a `RECURRING` row.
- Processing reads user decisions only from `user_decision` (`UserDecisionRepository.effectiveFor`). A correction is only its `CORRECTION` row there (no `user_correction` table since the restart at version 1). A bank choice is only its `BANK_SELECTION` row. `review_item` is still written with each outcome decision; only the files `DecisionReadBoundaryTest` names may read them (writers, stores, screens and the reads kept in `docs/ARCHITECTURE.md`, "Reading decisions").
- Correction, restore and ownership changes keep the manual decision and record durable retries. A restore that wrote `USER_FINANCIAL_TYPE` stays financial on failure (never rewritten to `USER_NON_FINANCIAL`). Full `refreshReviewQueue` with failures throws and records the ids.
- Startup waits for the financial store to open (`StartupMaintenance`) and fails closed; it runs no reconciliation pass, also not after a restore. Double-posted legacy legs are released by `LedgerProjector` in the next pass that reaches them. Background work is idempotent, keeps failed rows retryable and emits `MaintenanceCompletionSignal`.

## Dashboard and analysis
- Money totals live in `application/dashboard/*Builder|*Calculator` (module `dashboard-core`, pure JVM; its repository-reading classes stay in `app`) and `AnalysisPresentationBuilder`; Compose only displays. No summing, Mada/credit classification or aggregation in Composables.
- Row facts and owned account ids come from `DashboardOverview.transactionFacts` / `ownedAccountContainerIds`; detail scoping is `EntityTransactionScope`. Presentation never builds container ids or converts currency.
- Card type from `ParsedEventDetails.cardSmsChannel`, never SMS text. AlJazira credit = unlabeled `بطاقة`/`card` line plus available balance; a card line alone is null; debit markers win.
- One due per credit facility (primary and supplementaries share it). Mada cards have salary-period spending only. Loan repayments (`LOAN_REPAYMENT`, or `FEE` + `FINANCING_INSTALLMENT`) via `LoanRepaymentAttribution`.
- Use existing helpers: `CreditFacilitiesOverview.aggregateCreditSalaryPeriodSpending()`, `DebitCardOverview.salaryPeriodSpendingNet`, `AccountsSummary.totalInflow`.
- Evidence only through `DashboardEvidenceSource` (`DashboardEvidenceScope`); never `ParsedEventRepository.listAll()` or per-row `RawSmsRepository.getById` on the load path. Unlinked history needs an explicit fact query (superset-safe, ordered by event id). Loads are read-only.
- `DashboardProjectionBuilder` only composes; logic goes in the matching section projection (`AnalysisDashboardProjection`, `AccountsDashboardProjection`, `CardsDashboardProjection`, `CommitmentsDashboardProjection`) with inputs from `DashboardProjectionContext`.
- Chart choices never change balances, dues or full-scope totals; persist bank-qualified keys, never names or last4 alone.
- Tests: run `./gradlew :dashboard-core:test` (JUnit Jupiter, no Robolectric) and `app/src/test/.../application/dashboard/`; keep `DashboardFixtureCharacterizationTest` green; cover registry-linked Mada and Google Pay SMS without `خصمت من حساب`.
- `LedgerSnapshotTest` compares every ledger row with `testdata/ledger_snapshot/full_ledger.golden.txt`; only `MASROOF_UPDATE_LEDGER_SNAPSHOT=1 ./gradlew :app:testDebugUnitTest --tests '*LedgerSnapshotTest' --rerun` rewrites it (approved changes only); `LedgerShadowCorpusTest` compares the stored and projected ledgers with `testdata/ledger_snapshot/shadow_comparison.expected.txt` (owner-decided 2026-11-12, no differences; a new one goes to the owner; only `MASROOF_UPDATE_SHADOW_COMPARISON=1` rewrites it); `MASROOF_REBUILD_FIXTURES=1` with `--tests '*LedgerFixturesTest' --rerun` is the only way to rebuild the version 1 db/backups in `testdata/ledger_fixtures/v1/` (frozen once the owner's converted file is handed over).

## UI and design
- These rules keep screens consistent and cheap to build; they rank below everything above (see Priorities).
- Use `MasroofTheme`/`MaterialTheme` and `MasroofThemeExtras.extendedColors`; only theme tokens in `ui-kit/.../ui/theme/` (`MasroofSpacing`, `MasroofIconSizes`, `MasroofShapes`, `MasroofElevation`, `MasroofAnalyticsTokens`); no `dp`/`sp`/color literals in screens (`DesignTokenBoundaryTest`). Add a token only when nothing fits.
- Reuse shared components (`MasroofCard`, `MasroofSectionHeader`, `MasroofAmountText`, `MasroofBadge`, `MasroofValueBarRow`, `MasroofStackedBar`, `MasroofSecondaryScaffold`, ...) before writing new ones; a new shared visual goes in `ui-kit`.
- Contextual explanations use `MasroofGuidance` (help popup) rather than permanent paragraphs (`MasroofGuidanceBoundaryTest`). Status, errors and confirmations stay visible. Analysis cards prefer a chart, big number or badge.
- Every label in `values/` (Arabic, no tashkeel) and `values-en/`; Western digits via `AppDigits`/`MasroofText`.
- Sheets use `MasroofBottomSheet` (`MasroofSheetTokens`, fixed header, scrolling body, `MasroofSheetChoices`). Drafts apply only on Save/Apply; busy financial writes block dismissal.
- Navigation in `presentation/navigation/` (`MasroofRoot`, `HomeDestination`, `SettingsDestination`); Home uses `HomeBackStack` `push`/`pop`, never a fixed return screen; screen-local state is `rememberSaveable`.
- Chart category projections and filtering live in dashboard builders / `ChartDataWorkflow`.
