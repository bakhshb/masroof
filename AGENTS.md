# Masroof agent notes

Android app that turns Saudi bank SMS into a private ledger and dashboard. Arabic first (RTL), English second.
Reference: `docs/ARCHITECTURE.md` (modules), `docs/ANALYTICS.md` (analysis), `docs/DESIGN_SYSTEM.md` (UI tokens and components), `docs/SUSTAINABILITY_PLAN.md` (approved refactor plan, piece by piece).
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
Run this for each plan phase:
1. Plan: what changes and the tests that prove it is done. Any change to numbers or behavior the owner sees is asked before implementing. For a new screen, describe its layout in the plan using existing components; make a design mock-up only when the owner asks for one.
2. Implement with its tests, and run them.
3. Simplify the code and its tests once (`/simplify`), and rerun the tests.
4. Review for bugs (`/code-review`), fix, rerun the tests; repeat until the review finds no real bug. Owner decisions are reported, not looped on.

At the end of the PR: one review of the whole PR, then CI green including device tests, then report done.

### Work under `docs/SUSTAINABILITY_PLAN.md`
- One pull request per plan piece, to `main`, one piece at a time, each starting from the latest `main` (no rebases).
- Each piece is built in its own git worktree by an implementer agent (`.claude/agents/implementer.md`), then reviewed by the Opus reviewer agent (`.claude/agents/reviewer.md`); loop until the review finds no real bug. The Model column of each piece names who implements it (Sonnet for routine pieces, Opus for high-risk or intricate ones). Opus reviews every piece. The orchestrator passes the model override when the column says Opus, because the implementer agent definition defaults to Sonnet.
- Every piece deletes the code, tests, imports, fixtures and string resources it makes obsolete, and updates `AGENTS.md`, `CLAUDE.md` and `docs/` when a rule or module changes.
- Owner gates (marked Gate) stop and ask before implementing. The derived ledger replaces the old reconciliation path only after the owner approves every difference in the shadow comparison.
- No remote pattern download. Wiring stays manual; no DI library unless the owner asks.

## Never break
- Database `masroof.db`: the schema changes only through additive, tested Room migrations: a migration test from the committed v18 fixture, a device upgrade test, and v18 backup archives still import. Only schemas from v18 up to the current version are accepted (`MasroofDatabase.IDENTITY_HASHES`); older than v18, unknown and newer are rejected. A backup of an older schema migrates on its staged copy before it replaces the live file. Never a destructive fallback. A schema change needs an explicit design and device verification; no backfill without owner approval. Current schema: v20 (v19 adds `user_decision`; v20 backfills it, no structural change).
- Keep the application id, preference names, Keystore alias, worker identities and historical persisted keys.
- No Lab UI, replica app or parallel intake; no `lab`/`beta` source paths. Engine/model refreshes never reparse posted evidence or override human decisions.
- Never log SMS bodies, account numbers or secrets. Logs carry stage, failure count, masked id and retry state.
- PRs target `main`. Full tests run in PR/main CI only; never add `testDebugUnitTest` to `release.yml`.

## Layers
- `presentation` → `application` → `domain` / `parsing` / `data` / `sms` / `bank`; dependencies point inward. `PackageDependencyRulesTest` enforces it: fix the import, never add an exception.
- ViewModels call `application` workflows/services only. Presentation never imports `domain.ids|rules|repository|ownership|assembly|matching` or `domain.period`.
- `finance-core` owns `core.money`, value models, period/time policies, identities, classification/impact, transfer and statement matching (packages kept as `com.baraa.masroof.core.money` / `domain.*`). It gets facts and returns decisions; repositories, ownership, reconciliation writes, CSV and dashboard aggregation stay in `app`. Do not copy these into `app`. Both Money contracts stay distinct.
- `application` constructors take every service, repository, workflow and scheduler non-null; no `X? = null` collaborator. Tests pass a `NoOp*`/`Empty*` double from `testsupport/` (`NoOptionalCollaboratorsTest` lists the few exceptions; add none).
- `bank-engine` may import only `finance.value` contracts. Pure modules use `engine.bank`, `finance.value`, `ml`, `analytics`; app integration tests live under `integration`.

## SMS pipeline
- Live: `IncomingSmsReceiver` → `LiveSmsIntake` → `CaptureBankSmsUseCase` (durable `RawSms`) → `LiveSmsWorkScheduler` → `LiveSmsProcessingWorker` → `ProcessStoredSmsUseCase`. Work input is the rawSmsId only.
- Live `receivedAt` = PDU service-center time when valid (`LiveReceiptTimestamp`, earliest part), else the injected clock.
- Historical: `HistoricalSmsScanner` → `HistoricalSmsBatchProcessor`: parse each row, then one discovery/reconcile/review pass per batch, never per SMS.
- `ProcessRawSmsUseCase` only wraps `reparseAndStore` for bulk maintenance; tests capture-then-process through the `ingest` test helper. No orchestration under `sms/`.

## Bank routing and parsing
- `BankSmsRegistry` evaluates every adapter. `Ambiguous`/`SuspectedBank` are stored and reviewed, never parsed by a guess; never resolve by registration order. Adapters never re-check the sender.
- Sender detection is `SenderBankDetector` for every bank, and `SenderIdentity` (bank-engine) is the one same-sender rule. Only an equal sender is detected; a long sender stem or a body naming the bank is only `Suspected`.
- Reprocessing order: `user_selected_bank:<bankId>`, then the stored ParsedEvent bank, then a fresh route. `Matched` parses; only a genuine `NotMatched` may use the sole-adapter fallback. Queue refresh keeps the selection prefix.
- Match on `NormalizedSms.comparisonBody` after `ArabicTextFolding`; display with `normalizedSlice(range)`, never `comparisonBody`.
- Bank logic stays in `bank/*`; fill `ParsedEventDetails` at parse time (`cardSmsChannel`, balances, due dates). Dashboard code reads persisted facts only: never re-parses SMS or imports `bank.*`.
- Interpretation lives in `bank-engine` (`PatternFactInterpreter`, `RuleInterpreter`, explicit evidence/blockers). Production definitions: `app/src/main/assets/bank-patterns/`; Kotlin rule profiles only in `bank-engine/src/testFixtures`. Ownership: approval `application/patterns`, protection `application/ingestion`, composition `application/interpretation`, storage `data/patterns`, backup `application/backup`; update `docs/ARCHITECTURE.md` before moving any. No parallel runtime.
- Competing families stay UNKNOWN; OTP wins over quoted financial wording; refunds keep refund authority. Add regression tests for role/classification changes. The retired AlJazira parser/classifier/extractor never returns, test copies included.
- Only `ParseFinalizer` emits `ParseResult.Success`, and only with no ERROR from `DefaultParsedEventValidator`. Tighten via validator rules / `AutomaticUsePolicy`; validators never resolve ownership or type.
- `ml-assist` gives family hints, forecasts and recurring detection only: never money, ownership or posting.
- New bank: a role-rule pattern file in `app/src/main/assets/bank-patterns/` (served by `MessagePatternSmsAdapter`), fixtures under `testdata/`, and a `BankSmsAdapterContractCase` in `BankSmsAdapterContractTest` (financial + non-financial samples, positive/negative senders); no sample claimed by two adapters.

## Financial rules
- An SMS is evidence, not a transaction. Same bank never implies a self-transfer; cross-bank can be one; unknown ownership on either side blocks it. Incoming transfers are not automatically income, outgoing not automatically expense; a card bill payment is not an expense; a refund offsets a purchase, not income. Several messages may describe one transaction.
- Posting gate (`TransactionAssembler.isAutomationEligible`): only `ParseStatus.SUCCESS`, an automation-confirming correction (family/amount) or `USER_FINANCIAL_TYPE`. Merchant/counterparty edits never lift it; reconciliation never bypasses it.
- Auto-ignore and stale-link cleanup follow the parsed family (`OTP`, `NON_FINANCIAL`, `BALANCE_NOTICE`) via `InformationalMessagePolicy`; never re-read wording. `UNKNOWN` stays reviewable; the one exception is a message without an amount whose exact wording the user already marked not financial (`FamilyAssistBridge.rememberNonFinancial`), which returns to review if that decision is later contradicted.
- Time: `BankTransactionTimePolicy` is the only bank→zone map (AlJazira `Asia/Riyadh`; others keep the stored `occurredAtZone`). `resolveTransferAsExternal`/`resolveAsFinancialType` use `TransactionTiming.effectiveOccurredAt` and persist that zone. Dashboard code never branches on bank time zones.
- FX: `ExchangeRateEnrichmentWorkflow` persists rates; dashboards apply them in memory. A complete `(appliedExchangeRate, exchangeRateSource)` pair is immutable; repair a half pair from one resolution.

## Processing completion
- `ReconciliationCompletionPolicy` is the only reading of a reconciliation report: `failed > 0` or a throw = incomplete, even if rows posted. Pending-match and intentional non-financial outcomes are not failures. A stored ParsedEvent is not a posted row.
- Live failure → `DerivedIncomplete(RECONCILIATION)`, WorkManager retries; on exhaustion keep a `LIVE` retry row plus an error review; startup reschedules.
- Historical batch with failures stays `Incomplete`, writes one atomic `HISTORICAL_BATCH` set and clears it only after a later pass with `failed == 0` and a successful review update.
- Every explicit user decision is also appended to `user_decision` (append-only, never updated or deleted) in the same database transaction as its existing write; `MIGRATION_19_20` backfilled the decisions made before, under the owner-approved rule (only resolved user reviews, corrections and bank selections; see `docs/ARCHITECTURE.md`). Nothing reads it yet (plan 2.3).
- Correction, restore and ownership changes keep the manual decision and record durable retries. A restore that wrote `USER_FINANCIAL_TYPE` stays financial on failure (never rewritten to `USER_NON_FINANCIAL`). Full `refreshReviewQueue` with failures throws and records the ids.
- Startup waits for integrity repair (`StartupMaintenance`) and fails closed. Background work is idempotent, keeps failed rows retryable and emits `MaintenanceCompletionSignal`.

## Dashboard and analysis
- Money totals live in `application/dashboard/*Builder|*Calculator` and `AnalysisPresentationBuilder`; Compose only displays. No summing, Mada/credit classification or aggregation in Composables.
- Row facts and owned account ids come from `DashboardOverview.transactionFacts` / `ownedAccountContainerIds`; detail scoping is `EntityTransactionScope`. Presentation never builds container ids or converts currency.
- Card type from `ParsedEventDetails.cardSmsChannel`, never SMS text. AlJazira credit = unlabeled `بطاقة`/`card` line plus available balance; a card line alone is null; debit markers win.
- One due per credit facility (primary and supplementaries share it). Mada cards have salary-period spending only. Loan repayments (`LOAN_REPAYMENT`, or `FEE` + `FINANCING_INSTALLMENT`) via `LoanRepaymentAttribution`.
- Use existing helpers: `CreditFacilitiesOverview.aggregateCreditSalaryPeriodSpending()`, `DebitCardOverview.salaryPeriodSpendingNet`, `AccountsSummary.totalInflow`.
- Evidence only through `DashboardEvidenceSource` (`DashboardEvidenceScope`); never `ParsedEventRepository.listAll()` or per-row `RawSmsRepository.getById` on the load path. Unlinked history needs an explicit fact query (superset-safe, ordered by event id). Loads are read-only.
- `DashboardProjectionBuilder` only composes; logic goes in the matching section projection (`AnalysisDashboardProjection`, `AccountsDashboardProjection`, `CardsDashboardProjection`, `CommitmentsDashboardProjection`) with inputs from `DashboardProjectionContext`.
- Chart choices never change balances, dues or full-scope totals; persist bank-qualified keys, never names or last4 alone.
- Tests: run `app/src/test/.../application/dashboard/`; keep `DashboardFixtureCharacterizationTest` green; cover registry-linked Mada and Google Pay SMS without `خصمت من حساب`.
- `LedgerSnapshotTest` compares every ledger row with `testdata/ledger_snapshot/full_ledger.golden.txt`; only `MASROOF_UPDATE_LEDGER_SNAPSHOT=1 ./gradlew :app:testDebugUnitTest --tests '*LedgerSnapshotTest' --rerun` rewrites it (approved changes only); `MASROOF_REBUILD_V18_FIXTURES=1` with `--tests '*LedgerFixturesTest' --rerun` is the only way to rebuild the frozen v18 db/backups in `testdata/ledger_fixtures/v18/`.

## UI and design
- These rules keep screens consistent and cheap to build; they rank below everything above (see Priorities).
- Use `MasroofTheme`/`MaterialTheme` and `MasroofThemeExtras.extendedColors`; only theme tokens in `ui-kit/.../ui/theme/` (`MasroofSpacing`, `MasroofIconSizes`, `MasroofShapes`, `MasroofElevation`, `MasroofAnalyticsTokens`); no `dp`/`sp`/color literals in screens (`DesignTokenBoundaryTest`). Add a token only when nothing fits.
- Reuse shared components (`MasroofCard`, `MasroofSectionHeader`, `MasroofAmountText`, `MasroofBadge`, `MasroofValueBarRow`, `MasroofStackedBar`, `MasroofSecondaryScaffold`, ...) before writing new ones; a new shared visual goes in `ui-kit`.
- Contextual explanations use `MasroofGuidance` (help popup) rather than permanent paragraphs (`MasroofGuidanceBoundaryTest`). Status, errors and confirmations stay visible. Analysis cards prefer a chart, big number or badge.
- Every label in `values/` (Arabic, no tashkeel) and `values-en/`; Western digits via `AppDigits`/`MasroofText`.
- Sheets use `MasroofBottomSheet` (`MasroofSheetTokens`, fixed header, scrolling body, `MasroofSheetChoices`). Drafts apply only on Save/Apply; busy financial writes block dismissal.
- Navigation in `presentation/navigation/` (`MasroofRoot`, `HomeDestination`, `SettingsDestination`); Home uses `HomeBackStack` `push`/`pop`, never a fixed return screen; screen-local state is `rememberSaveable`.
- Chart category projections and filtering live in dashboard builders / `ChartDataWorkflow`.
