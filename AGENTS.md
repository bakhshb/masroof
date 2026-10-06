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

**SMS:** Live intake = `IncomingSmsReceiver` → `LiveSmsIntake` → `ProcessRawSmsUseCase`. Historical scan = `application/sms/HistoricalSmsScanner`. Do not add orchestration under `sms/`.

**Parsing vs dashboard:** Bank-specific logic stays in `bank/*` parsers. Populate `ParsedEventDetails` at parse time (`cardSmsChannel`, balances, due dates, etc.). Dashboard code in `application/dashboard/*` reads persisted facts only — never re-parse SMS text and never import `bank.*`.

**Room changes:** Migration + mapper + parser population + migration test. If existing users need the new column filled, wire backfill (see `ParsedEventFactsBackfillCoordinator`). Device-test after schema/backfill merges.

**Parse-status gate:** Only `ParseStatus.SUCCESS` (or user-corrected) evidence may create/pair/post a `FinancialTransaction` (`TransactionAssembler.isAutomationEligible`). Never bypass it in reconciliation passes.

**Bank routing:** `BankSmsRegistry` evaluates every adapter; `BankRoutingResult.Ambiguous` is persisted and reviewed (`ambiguous_bank_route`), never parsed by a guessed adapter. Never resolve collisions by registration order. The router owns detection: adapter parse pipelines must not re-check the sender.

**New bank:** Implement `BankSmsAdapter`, add fixture tests under `testdata/`, and register a `BankSmsAdapterContractCase` (real financial + non-financial fixtures, positive/negative senders) in `BankSmsAdapterContractTest`; no sample may be claimed by more than one adapter.

**PRs:** Target `main` only. Partial architecture merges may show broken UI until backfill lands — that is expected.

**Release CI:** Full tests run in PR/main **CI** only. Do not add `testDebugUnitTest` back to `release.yml`; release verifies green CI then builds the APK.

**Deep reference:** `docs/ARCHITECTURE.md`, `docs/REWRITE_DECISIONS.md`

## Dashboard calculations

- All money totals live in `application/dashboard/*Builder` and `*Calculator`.
- Compose screens in `presentation/dashboard` must display pre-computed values only.
- Do not sum transactions, classify Mada vs credit, or aggregate spending inside Composables.
- Classify card type via `ParsedEventDetails.cardSmsChannel` — not SMS body text in dashboard code.
- Use helpers such as `CreditFacilitiesOverview.aggregateCreditSalaryPeriodSpending()`, `DebitCardOverview.salaryPeriodSpendingNet`, and `AccountsSummary.totalInflow`.
- Credit facility due is one value per facility (primary + supplementaries share the statement due).
- Mada (debit) cards have salary-period spending only — no statement due.
- Loan repayments are detected from `LOAN_REPAYMENT` or `FEE` + `FINANCING_INSTALLMENT` SMS via `LoanRepaymentAttribution`; all dashboard calculators must use it.

## Testing dashboard changes

- Run targeted unit tests under `app/src/test/kotlin/com/baraa/masroof/application/dashboard/`.
- For Mada linking, cover both registry-linked cards and Google Pay SMS without `خصمت من حساب`.
