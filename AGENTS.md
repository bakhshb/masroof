# Masroof agent notes

## Design system and theme

- All UI must follow the shared theme in `presentation/theme/` — do not introduce ad-hoc colors, typography, spacing, or icon sizes.
- Use `MasroofTheme` / `MaterialTheme` for colors and typography; extended semantic colors live in `MasroofThemeExtras.extendedColors`.
- Spacing and sizing tokens: `MasroofSpacing`, `MasroofIconSizes`, `MasroofElevation`, `MasroofShapes`.
- Reuse shared components from `presentation/common/` (`MasroofCard`, `MasroofSectionHeader`, `MasroofAmountText`, `MasroofMoneyRow`, `MasroofSecondaryScaffold`, etc.) instead of one-off layouts.
- Do not add hardcoded `dp` values in feature screens when an existing token fits; add a new token in `presentation/theme/` only when the design truly needs a new size.
- App navigation lives in `presentation/navigation/` (`MasroofRoot`, `HomeDestination`, `SettingsDestination`).
- Debug builds expose a design catalog at **Settings → About → Design catalog** (`presentation/debug/DesignCatalogScreen.kt`) for previewing tokens and shared components.

## Architecture (follow on every feature)

**Layers:** `presentation` → `application` → `domain` / `parsing` / `data` / `sms` / `bank`. Dependencies point inward. Rules are enforced in `PackageDependencyRulesTest` — do not add exceptions; fix the import instead.

**ViewModels:** Call `application/*Workflow` facades only. No direct use of `domain.repository`, `domain.ownership`, or `domain.period` from presentation.

**SMS:** Live intake = `IncomingSmsReceiver` → `LiveSmsIntake` → `CaptureBankSmsUseCase` (durable `RawSms`) → `LiveSmsWorkScheduler` → `LiveSmsProcessingWorker` → `ProcessStoredSmsUseCase`. Live `receivedAt` is the PDU service-center timestamp when valid (`LiveReceiptTimestamp`, earliest part for multipart); otherwise the injected device clock. Work input is the rawSmsId only — never SMS body/OTP text. Historical scan = `application/sms/HistoricalSmsScanner` → `HistoricalSmsBatchProcessor` (parse each row, then one discovery/reconcile/review pass per batch — never reconcile per historical SMS). `ProcessRawSmsUseCase` is only a capture-then-process facade. Do not add orchestration under `sms/`.

**Parsing vs dashboard:** Bank-specific logic stays in `bank/*` parsers. Populate `ParsedEventDetails` at parse time (`cardSmsChannel`, balances, due dates, etc.). Dashboard code in `application/dashboard/*` reads persisted facts only — never re-parse SMS text and never import `bank.*`.

**Room changes:** Migration + mapper + parser population + migration test. If existing users need the new column filled, wire backfill (see `ParsedEventFactsBackfillCoordinator`). Declare the new schema version in `SchemaFactsBackfillPolicy`: `BLOCKING` only if existing rows display incorrectly until re-parse, otherwise `BACKGROUND`. Device-test after schema/backfill merges.

**Maintenance:** Startup waits for `BLOCKING` maintenance (`StartupMaintenance`) and fails closed if it is incomplete: never expose financial screens until a blocking retry succeeds. Only `BACKGROUND` work may run while the app is open; it must be idempotent, keep failed rows retryable, and emit `MaintenanceCompletionSignal` so screens refresh.

**Parse-status gate:** Only `ParseStatus.SUCCESS`, an explicit automation-confirming correction (message family/amount), or an explicit `USER_FINANCIAL_TYPE` resolution may create/pair/post a `FinancialTransaction` (`TransactionAssembler.isAutomationEligible`). Merchant/counterparty-only edits never lift the gate. Never bypass it in reconciliation passes.

**Informational authority:** Auto-ignore and stale-link cleanup follow the parsed message family (`OTP`, `NON_FINANCIAL`, `BALANCE_NOTICE`) via `InformationalMessagePolicy`. Reconciliation and review dismiss do not re-read SMS wording. `UNKNOWN` stays reviewable.

**Transaction time:** Convert SMS wall clocks with `BankTransactionTimePolicy`, the only bank-to-zone map. AlJazira uses `Asia/Riyadh`. Other banks keep the zone stored on first assembly (`occurredAtZone`). Dashboard code does not branch on a bank timezone.

**Bank routing:** `BankSmsRegistry` evaluates every adapter; `Ambiguous` and `SuspectedBank` are persisted and reviewed, never parsed by a guessed adapter. Never resolve collisions by registration order. The router owns detection: adapter parse pipelines must not re-check the sender. Stored reprocessing selects an explicit `user_selected_bank:<bankId>` choice, then the stored ParsedEvent bank, then a fresh route. `Matched` parses. `Ambiguous` and `SuspectedBank` stay in review. Only a genuine `NotMatched` route may use the sole-adapter fallback. Queue refresh preserves the selection prefix. Selecting a bank does not change detector rules.

**SMS text matching:** Match on `NormalizedSms.comparisonBody` with `comparisonRegex(...)` / `containsComparison(...)` (Arabic-folded patterns); never display `comparisonBody` — slice display values with `normalizedSlice(range)`.

**Classification:** Add AlJazira family wording as an `AlJaziraClassificationRule` with an explicit `AlJaziraClassificationSpecificity` tier — never rely on rule order. Competing families in the top tier must resolve to `UNKNOWN` (review); add a `collision_*` fixture for every new tie-break. An informational top tier (notice, balance, statement) must not hide a product or movement candidate: that cross-tier pair is also `UNKNOWN` with both families in the evidence. OTP stays security even when the body quotes a purchase. A refund title that names the purchase it reverses stays `REFUND`.

**Validation firewall:** Only `ParseFinalizer` may emit `ParseResult.Success`, and only when `DefaultParsedEventValidator` reports no ERROR findings. Tighten automatic use through validator rules / `AutomaticUsePolicy`, not by special-casing parsers; validators never resolve ownership or transaction type.

**New bank:** Implement `BankSmsAdapter`, add fixture tests under `testdata/`, and register a `BankSmsAdapterContractCase` (real financial + non-financial fixtures, positive/negative senders) in `BankSmsAdapterContractTest`; no sample may be claimed by more than one adapter.

**PRs:** Target `main` only. Partial architecture merges may show broken UI until backfill lands — that is expected.

**Release CI:** Full tests run in PR/main **CI** only. Do not add `testDebugUnitTest` back to `release.yml`; release verifies green CI then builds the APK.

**Deep reference:** `docs/ARCHITECTURE.md`, `docs/REWRITE_DECISIONS.md`

## Dashboard calculations

- All money totals live in `application/dashboard/*Builder` and `*Calculator`.
- Compose screens in `presentation/dashboard` must display pre-computed values only.
- Do not sum transactions, classify Mada vs credit, or aggregate spending inside Composables.
- Row-level facts (card last4, effective type, SAR equivalent) and owned account container ids come from `DashboardOverview.transactionFacts` / `ownedAccountContainerIds`; ViewModels and navigation never build container ids or call conversion helpers (enforced in `PackageDependencyRulesTest`).
- Classify card type via `ParsedEventDetails.cardSmsChannel` — not SMS body text in dashboard code.
- Use helpers such as `CreditFacilitiesOverview.aggregateCreditSalaryPeriodSpending()`, `DebitCardOverview.salaryPeriodSpendingNet`, and `AccountsSummary.totalInflow`.
- Credit facility due is one value per facility (primary + supplementaries share the statement due).
- Mada (debit) cards have salary-period spending only — no statement due.
- Loan repayments are detected from `LOAN_REPAYMENT` or `FEE` + `FINANCING_INSTALLMENT` SMS via `LoanRepaymentAttribution`; all dashboard calculators must use it.
- Dashboard evidence is scoped: read parsed/raw SMS only through `DashboardEvidenceSource` (`DashboardEvidenceScope`). Never call `ParsedEventRepository.listAll()` or per-row `RawSmsRepository.getById` on the dashboard load path; a rule needing unlinked history gets an explicit fact query (superset-safe, ordered by event id).
- `DashboardProjectionBuilder` is composition only. Add dashboard logic to the matching section (`AnalysisDashboardProjection`, `AccountsDashboardProjection`, `CardsDashboardProjection`, `CommitmentsDashboardProjection`) via specialist builders/calculators; shared inputs come from `DashboardProjectionContext`, never a second repository read.
- Dashboard loads are read-only: never write from `application/dashboard/*` projection code. Persisted FX enrichment belongs to `ExchangeRateEnrichmentWorkflow`; the dashboard applies resolved rates in memory only.

## Testing dashboard changes

- Run targeted unit tests under `app/src/test/kotlin/com/baraa/masroof/application/dashboard/`.
- Evidence-scope changes must keep `DashboardFixtureCharacterizationTest` scoped-vs-whole-history equality green.
- For Mada linking, cover both registry-linked cards and Google Pay SMS without `خصمت من حساب`.
