# Masroof agent notes

Android app that turns Saudi bank SMS into a private ledger and dashboard. Arabic first (RTL), English second.
Reference: `docs/ARCHITECTURE.md` (modules), `docs/DESIGN_SYSTEM.md` (UI), `docs/ANALYTICS.md` (analysis).
This file wins when older records disagree.

## Workflow
Every change runs this loop; stop only when both reviews come back clean.
1. Plan: what changes and how we know it is done (checks a person can run). A screen design first gets an interactive design artifact the owner confirms.
2. Implement.
3. Review for simplification (`/simplify`).
4. Review for bugs (`/code-review`).
5. Fix what both found, then repeat 3-5 until neither finds anything.

## Never break
- Database `masroof.db` stays schema v18 with the same identity hash; reject other database/backup schemas, no destructive fallback, no migrations or backfills. A schema change needs an explicit design and device verification.
- Keep the application id, preference names, Keystore alias, worker identities and historical persisted keys.
- No Lab UI, replica app or parallel intake; no `lab`/`beta` source paths. Engine/model refreshes never reparse posted evidence or override human decisions.
- Never log SMS bodies, account numbers or secrets. Logs carry stage, failure count, masked id and retry state.
- PRs target `main`. Full tests run in PR/main CI only; never add `testDebugUnitTest` to `release.yml`.

## Layers
- `presentation` → `application` → `domain` / `parsing` / `data` / `sms` / `bank`; dependencies point inward. `PackageDependencyRulesTest` enforces it: fix the import, never add an exception.
- ViewModels call `application` workflows/services only. Presentation never imports `domain.ids|rules|repository|ownership|assembly|matching` or `domain.period`.
- `finance-core` owns `core.money`, value models, period/time policies, identities, classification/impact, transfer and statement matching (packages kept as `com.baraa.masroof.core.money` / `domain.*`). It gets facts and returns decisions; repositories, ownership, reconciliation writes, CSV and dashboard aggregation stay in `app`. Do not copy these into `app`. Both Money contracts stay distinct.
- `bank-engine` may import only `finance.value` contracts. Pure modules use `engine.bank`, `finance.value`, `ml`, `analytics`; app integration tests live under `integration`.

## SMS pipeline
- Live: `IncomingSmsReceiver` → `LiveSmsIntake` → `CaptureBankSmsUseCase` (durable `RawSms`) → `LiveSmsWorkScheduler` → `LiveSmsProcessingWorker` → `ProcessStoredSmsUseCase`. Work input is the rawSmsId only.
- Live `receivedAt` = PDU service-center time when valid (`LiveReceiptTimestamp`, earliest part), else the injected clock.
- Historical: `HistoricalSmsScanner` → `HistoricalSmsBatchProcessor`: parse each row, then one discovery/reconcile/review pass per batch, never per SMS.
- `ProcessRawSmsUseCase` is only a capture-then-process facade. No orchestration under `sms/`.

## Bank routing and parsing
- `BankSmsRegistry` evaluates every adapter. `Ambiguous`/`SuspectedBank` are stored and reviewed, never parsed by a guess; never resolve by registration order. Adapters never re-check the sender.
- Reprocessing order: `user_selected_bank:<bankId>`, then the stored ParsedEvent bank, then a fresh route. `Matched` parses; only a genuine `NotMatched` may use the sole-adapter fallback. Queue refresh keeps the selection prefix.
- Match on `NormalizedSms.comparisonBody` after `ArabicTextFolding`; display with `normalizedSlice(range)`, never `comparisonBody`.
- Bank logic stays in `bank/*`; fill `ParsedEventDetails` at parse time (`cardSmsChannel`, balances, due dates). Dashboard code reads persisted facts only: never re-parses SMS or imports `bank.*`.
- Interpretation lives in `bank-engine` (`PatternFactInterpreter`, `RuleInterpreter`, explicit evidence/blockers). Production definitions: `app/src/main/assets/bank-patterns/`; Kotlin rule profiles only in `bank-engine/src/testFixtures`. Ownership: approval `application/patterns`, protection `application/ingestion`, composition `application/interpretation`, storage `data/patterns`, backup `application/backup`; update `docs/ARCHITECTURE.md` before moving any. No parallel runtime.
- Competing families stay UNKNOWN; OTP wins over quoted financial wording; refunds keep refund authority. Add regression tests for role/classification changes. The retired AlJazira parser/classifier/extractor never returns, test copies included.
- Only `ParseFinalizer` emits `ParseResult.Success`, and only with no ERROR from `DefaultParsedEventValidator`. Tighten via validator rules / `AutomaticUsePolicy`; validators never resolve ownership or type.
- `ml-assist` gives family hints, forecasts and recurring detection only: never money, ownership or posting.
- New bank: a role-rule pattern file in `app/src/main/assets/bank-patterns/` (served by `MessagePatternSmsAdapter`), fixtures under `testdata/`, and a `BankSmsAdapterContractCase` in `BankSmsAdapterContractTest` (financial + non-financial samples, positive/negative senders); no sample claimed by two adapters.

## Financial rules
- Posting gate (`TransactionAssembler.isAutomationEligible`): only `ParseStatus.SUCCESS`, an automation-confirming correction (family/amount) or `USER_FINANCIAL_TYPE`. Merchant/counterparty edits never lift it; reconciliation never bypasses it.
- Auto-ignore and stale-link cleanup follow the parsed family (`OTP`, `NON_FINANCIAL`, `BALANCE_NOTICE`) via `InformationalMessagePolicy`; never re-read wording. `UNKNOWN` stays reviewable; the one exception is a message without an amount whose exact wording the user already marked not financial (`FamilyAssistBridge.rememberNonFinancial`), which returns to review if that decision is later contradicted.
- Time: `BankTransactionTimePolicy` is the only bank→zone map (AlJazira `Asia/Riyadh`; others keep the stored `occurredAtZone`). `resolveTransferAsExternal`/`resolveAsFinancialType` use `TransactionTiming.effectiveOccurredAt` and persist that zone. Dashboard code never branches on bank time zones.
- FX: `ExchangeRateEnrichmentWorkflow` persists rates; dashboards apply them in memory. A complete `(appliedExchangeRate, exchangeRateSource)` pair is immutable; repair a half pair from one resolution.

## Processing completion
- `ReconciliationCompletionPolicy` is the only reading of a reconciliation report: `failed > 0` or a throw = incomplete, even if rows posted. Pending-match and intentional non-financial outcomes are not failures. A stored ParsedEvent is not a posted row.
- Live failure → `DerivedIncomplete(RECONCILIATION)`, WorkManager retries; on exhaustion keep a `LIVE` retry row plus an error review; startup reschedules.
- Historical batch with failures stays `Incomplete`, writes one atomic `HISTORICAL_BATCH` set and clears it only after a later pass with `failed == 0` and a successful review update.
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

## UI and design
- Use `MasroofTheme`/`MaterialTheme` and `MasroofThemeExtras.extendedColors`; only theme tokens in `ui-kit/.../ui/theme/` (`MasroofSpacing`, `MasroofIconSizes`, `MasroofShapes`, `MasroofElevation`, `MasroofAnalyticsTokens`); no `dp`/`sp`/color literals in screens (`ApprovedDesignBoundaryTest`). Add a token only when nothing fits.
- Reuse shared components (`MasroofCard`, `MasroofSectionHeader`, `MasroofAmountText`, `MasroofBadge`, `MasroofValueBarRow`, `MasroofStackedBar`, `MasroofSecondaryScaffold`, ...). New shared visuals go in `ui-kit` and the debug catalog (Settings → About → Design catalog).
- Explanations only through `MasroofGuidance` (help popup), never paragraphs. Analysis screens are visual first: chart, big number or badge per card.
- Every label in `values/` (Arabic, no tashkeel) and `values-en/`; Western digits via `AppDigits`/`MasroofText`.
- Sheets use `MasroofBottomSheet` (`MasroofSheetTokens`, fixed header, scrolling body, `MasroofSheetChoices`). Drafts apply only on Save/Apply; busy financial writes block dismissal.
- Navigation in `presentation/navigation/` (`MasroofRoot`, `HomeDestination`, `SettingsDestination`); Home uses `HomeBackStack` `push`/`pop`, never a fixed return screen; screen-local state is `rememberSaveable`.
- Chart category projections and filtering live in dashboard builders / `ChartDataWorkflow`.
