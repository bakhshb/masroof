# Production architecture

Masroof has one Android application, one financial database and one bank interpretation engine. Planned structure changes are listed in [the sustainability plan](SUSTAINABILITY_PLAN.md); this document describes the code as it is. The tested engine and advisory features are integrated into the existing app; the parallel Lab application and generated source replica are removed.

## Modules

- `app`: presentation → application workflows → domain/parsing/data/SMS/bank ports and the pure financial core. `AppContainer` is the manual composition root of the only production runtime (see "Wiring" below).
- `bank-engine`: pure Kotlin message-pattern matching, literal-role interpretation and extraction evidence, depending only on `finance-core` value contracts. `MessagePatternSmsAdapter` maps its output through `InterpretationDraftMapper` and the production `ParseFinalizer`.
- `finance-core`: production money/value models, salary and statement periods, bank transaction time, deterministic container/transaction identities, financial classification/impact, transfer matching and assembly, and statement matching. Policies consume supplied facts and return decisions or transactions in memory; there is no ledger, repository, Android dependency or I/O. Existing engine value contracts remain available separately.
- `ledger`: the pure derived-ledger projector (plan 3.1), depending only on `finance-core`. Since plan 3.3a it is how reconciliation writes the ledger; see "Derived ledger" and "Ledger writer" below.
- `dashboard-core` (plan 4.1): the pure JVM dashboard read model, depending only on `finance-core`: the `*Builder`, `*Calculator`, `*Overview`, `*Resolver`, `*Classifier`, `*Scope` and value types of `application/dashboard`, plus the Accounts and Analysis section projections, and `DashboardOverview`/`DashboardEvidence`/`DashboardProjectionContext`. Packages stay `com.baraa.masroof.application.dashboard`. What loads data stays in `app`: `DashboardService`, `DashboardProjectionBuilder`, the Cards and Commitments projections (they read repositories), `DashboardEvidenceScope`/`TransactionSmsEvidenceLoader`, `DashboardCommitmentsWorkflow`/`DashboardRegistryWorkflow`/`DashboardPeriodWorkflow` (repositories, clock), `ChartDataWorkflow` (kotlinx.serialization/coroutines) and `AnalysisSpendingInsightsBuilder` (`@Serializable` output). Tests that need Robolectric or the app database (`DashboardFixtureCharacterizationTest`, `LedgerSnapshotTest`, `DashboardProjectionBuilderTest`, the golden-ledger tests) stay in `app`.
- `data` (plan 4.2): an Android library holding Room and everything stored through it, plus the preference stores. `data/room` (the three databases `MasroofDatabase`, `LearningDatabase`, `AnalyticsDatabase`, entities, DAOs, mappers, migrations, `PreservingOpenHelperFactory`, `DatabaseAccessGate`), `data/repository` (the `Room*Repository` classes), `data/learning`, `data/analytics` and `data/preferences` (the SharedPreferences stores). It depends on `finance-core` and `ml-assist` and never on `app`; `app` applies neither Room nor KSP. Room schemas export to `data/schemas` (one committed file per database, version 1). The ports these stores implement moved down with them under their original packages: `domain.repository`, `parsing.repository.ParsedEventRepository`, the preference interfaces (`application.theme|locale|notification|onboarding|update`, `DashboardLayoutPreferencesRepository`) and `domain.ownership.RegistryIdentity`; the plain models they use (`domain.model.ReviewItem`, `CardConfiguration`, ...) and `ReviewIdFactory` moved to `finance-core`. Test fixtures of `:data` (`NextVersionTestDatabase`, `UserDecisionBackfill`, `NoOpLoanRegistryRepository`) are used by `app` unit and device tests. Staying in `app`: `data/patterns` (the pattern store over the `pattern_configuration` row, its codecs), `data/exchange` (OkHttp client), `data/statement` (CSV parser over statement ports) and `SharedPrefsChartDataPreferencesRepository` (its `ChartDataPreferences` type lives with `ChartDataWorkflow`).
- `ml-assist`: exact human-template family memory, cleaned templates, forecasting and recurring-pattern detection. The engine consumes an injected assistance contract rather than depending on ML implementation.
- `ui-kit`: shared theme, typography, fonts, cards and charts. It cannot access financial storage.

Source namespaces use `com.baraa.masroof.engine.bank`, `com.baraa.masroof.finance.value`,
`com.baraa.masroof.ml`, `com.baraa.masroof.analytics` and `com.baraa.masroof.ledger`. `dashboard-core` keeps `com.baraa.masroof.application.dashboard`, and `finance-core` also holds the plain value models the dashboard reads (`RawSms`, registry entries, `Commitment`, `ParsedEventDetails`, `ParsedEventRecord`, `AppLocale`) under their original packages. Application integration
tests use `com.baraa.masroof.integration`. Historical `lab`/`beta` source paths and
source file prefixes have been removed. Existing serialized backup keys and retired
work identifiers keep their original spelling for upgrade compatibility.

## Wiring

`AppContainer` is a thin root that builds one plain feature container per area (`application/container/`). There is no DI library.
Each container takes the objects it needs from the ones built before it, so every shared object (database, access gate, repositories,
completion signal, schedulers, app log, stores) exists once. A dependency that would form a cycle is read lazily through a lambda.

| Order | Container | Owns |
|---|---|---|
| 1 | `StorageContainer` | restore recovery, Room database (whose open copies the legacy pattern file and seeds bundled patterns), pattern store, access gate, repositories, preferences, app log, application scope |
| 2 | `MaintenanceContainer` | startup maintenance (the financial store opens, else BLOCKED) and its outcome, `MaintenanceCompletionSignal` |
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
default factory. `DatabaseRestoreRecovery.recover` runs first, inside `StorageContainer`, before Room is created.
ViewModel factories take the feature containers they read from. Test seams: `bankAdapterOverride`, `smsDataSourceOverride`,
`databaseOpenHelperFactory`, `restartAfterRestore`.

## Message-pattern ownership

Keep the existing modules. Organize by responsibility; a filename containing
`Pattern` does not mean its approval, storage or UI belongs in the engine.

| Location (under `com.baraa.masroof`) | Owns |
| --- | --- |
| `bank-engine: engine.bank.patterns` | Immutable definitions, compilation, matching, extracted facts and provenance |
| `bank-engine: engine.bank.interpretation` | Shared role, text, money, date and safety primitives |
| `app: bank` | Bank routing/detection and mapping engine output into validated parser drafts |
| `app: application.patterns` | Teaching, revisions, financial approval, pending preview/application and display models |
| `app: application.interpretation` | Configured adapter snapshots, family assistance and automatic-write eligibility |
| `app: application.ingestion` | Durable processing, protected evidence and retry/resume orchestration |
| `app: data.patterns` | Strict JSON codecs, immutable revision persistence in the `pattern_configuration` row |
| `app: application.backup` | Conversion of older archives' patterns, restore and rollback integration |
| `app: presentation.patterns` | Render application models and collect user input |
| `finance-core` | Financial classification, matching and assembly from supplied facts |
| `dashboard-core` | Dashboard builders, calculators and section projections from supplied facts (no repositories) |
| `data` | Room databases and schemas, DAOs, Room repositories, preference stores, and the ports they implement |
| `ledger` | Derived ledger from evidence, decisions and ownership; since plan 3.3a it is how reconciliation writes the ledger |

`StorageContainer` loads `app/src/main/assets/bank-patterns/*.patterns.json`, installs
missing bundled definitions when the live database opens (before anything can read the
patterns, see [Pattern configuration](#pattern-configuration-pattern_configuration)), and wires the store into
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
RawSms, parsed evidence, corrections or posted financial rows. Deletion adds no column
or table; deleted patterns cannot be recovered through revision restore.

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
transaction. Dashboard builders and calculators live in `dashboard-core`; evidence
scoping and the dashboard classes that read repositories stay in `app`. The statement match policy and direction value live in core; canonical
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

## Derived ledger (plan 3.1)

`:ledger` holds `LedgerProjector`, a pure function from facts to the ledger. Since plan 3.3a `TransactionReconciliationService` writes what it projects ("Ledger writer" below); "Shadow comparison" compares the stored ledger with it.

`LedgerProjector.project(events, decisions, registry, storedFacts, zone)` takes:

| Input | Meaning |
|---|---|
| `events: List<LedgerEvidence>` | One per raw SMS: the effective `ParsedEvent` (corrections applied), the raw SMS receipt time, and the parse details the ledger reads (reference, SMS-local time, loan type, `automationConfirmed`) |
| `decisions: Map<String, EffectiveUserDecisions>` | The 2.3 read model by raw SMS id; `EffectiveUserDecisions` and `UserDecision` now live in `finance-core` (same package) |
| `registry: OwnershipFacts` | Ownership already resolved for each referenced account, card and loan (the app keeps the registry's suffix fallback); a missing one is UNKNOWN |
| `storedFacts: StoredFacts` | The stored rows themselves (`LedgerRow`: transaction plus linked raw SMS), read for their zone and exchange-rate pair and kept verbatim by the keep rule; and the raw SMS whose review is closed as `USER_CORRECTION` (`ReviewCorrectionClosure`) |
| `zone` | The fallback zone for a bank with neither a fixed nor a stored zone |

It returns `LedgerProjection`: the rows (transaction plus its sorted raw SMS ids), review candidates (`ReviewKind`, now in `finance-core`), the settled ids, the retry ids (a decision or upgrade that could not be saved), and `confirmedLoans`. Loan auto-confirm is a pre-pass: an eligible installment paid from an owned account confirms an unknown loan for every installment of the run, and the caller records the emitted facts in the registry.

The rules, applied to an empty ledger in event-id order: a non-financial decision settles; a posting decision rebuilds the user's row (`decidedPosting`); an external-transfer decision without one settles; a financial decision lifts the posting gate; then single assembly with the stored zone, mutually unique pairing, the lone-owned-leg fallback (a pair or lone leg is read in the stored zone of a leg that has a row, as the old heal read it, so a bank without a fixed zone keeps its zone), and the upgrade of posted external or single-leg self-transfer legs. A row keeps its stored zone through `TransactionTiming`, and keeps a complete stored exchange-rate pair when it links the same raw SMS set. Ids are `TransactionIdFactory.fromRawSmsIds`.

Keep rule (owner, gate 3.2, 2026-11-12). A stored row is carried into the projection verbatim (type, amount, time, zone, containers, merchant, counterparty, id, exchange-rate pair), its raw SMS settled and given no review candidate, when:

1. any of its raw SMS is protected: a correction closure (`ReviewCorrectionClosure`), kept verbatim because the old pass never re-opened, paired, healed or released a protected row (a kept single-leg self-transfer still counts as a pending counterpart, so an intra-bank counterpart near it stays a pending match, as before, and an inter-bank one is posted on its own, as before); or an outcome as its latest decision: a posting outcome (`EXTERNAL_TRANSFER`, `FINANCIAL_TYPE`, `RECLASSIFY`, `RESTORE`, `SELF_TRANSFER_PAIR`), with or without posting columns, where the stored row wins over the decision's own columns; or a not-financial outcome (`NON_FINANCIAL`, `IGNORE`, `RESTORE_REVERTED`), which settles the SMS and derives nothing for it but keeps a row that still links it, as the old pass skipped such an SMS and only the decision's own writer deletes the row (owner, 2026-11-12: no existing row vanishes through the switch). These rows are saved first, before any decided posting, so a posting from another SMS's decision that names one of their SMS conflicts and is retried; their SMS never join the external-pair upgrade; or
2. it links two raw SMS (a pair) and none is protected. It is settled as that pair before any leg is assembled, so its legs never re-enter the matching pool (the old pass left linked legs alone; only its upgrade passes touched posted single legs). It takes today's detail (zone, time, containers, counterparty, id) when its legs alone re-derive exactly it with the same type and amount, else stays verbatim. A transfer split by a daily limit therefore keeps its stored pair [a1,b]: a later out-leg a2 is posted on its own as a single-leg self-transfer, as the old pass posted it (b was linked), and a2 joins its own in-leg b2 by the single-leg upgrade when the two match (an inter-bank pair needs a shared reference to match; without one both stay single, as before); or
3. it links three or more raw SMS (several messages about one movement, or an old wrong merge): it is settled as stored before any leg is assembled, except the old stale-link release, decided per raw SMS as the old pass decided it: an unprotected transfer leg of a stored `SELF_TRANSFER` whose effective time, read in the row's zone, differs from the row's time (by more than the match window while the row still links two parsed events) leaves the row, leg by leg in event order, and is an unlinked leg again, open to assembly and to the upgrade. A row the release leaves with one leg is a posted single-leg self-transfer the upgrade may join, as before; or
4. for a row of one raw SMS (or none), run last, the one place a stored row may be replaced. While legs are assembled, a leg its own stored row links and that would wait for a counterpart stays out of the matching pool with that row, as the old pass left a linked leg alone; only the upgrade may join it. The row is replaced only by (a) exactly one derived row on the same raw SMS set with the same type and amount (the old pass's fee→financing heal, stored `FEE`, derived `LOAN_REPAYMENT`, excepted), or (b) a derived self-transfer pair joining a stored external transfer or single-leg self-transfer leg with its counterpart where the posted-leg upgrade joins them: mutually unique, owned at both ends, among posted external or single-leg legs and unlinked eligible, unprotected legs, or (c) it is dropped as a lone, unprotected informational row (below). Otherwise it stays verbatim and the derived rows on its raw SMS are dropped; a raw SMS of theirs that no row links any more is assembled alone again (the old lone-owned-leg step, against the legs still open), else it is a pending match. A row that links no raw SMS stays byte for byte (it takes no exchange-rate pair from another such row) unless a derived row took its id. A kept row's SMS are removed from the retry ids. `LedgerProjectorPropertyTest.everyStoredRowIsKeptVerbatimOrReplacedByAnAllowedRule` checks this on random corpora.

A stored single-leg self-transfer outside the matching pool (kept by any rule) still counts as a pending counterpart, so an intra-bank leg near it stays pending instead of being posted alone.

Neither applies when the row links one SMS of an informational family (`InformationalMessagePolicy`) that no outcome decision or correction closure protects: that row is removed, as the old stale-link cleanup removes it.

Double-posted legs (owner, 2026-11-12, plan 3.3c). Before any rule above, an unprotected stored single-leg self-transfer of one raw SMS (one linked transfer-family event) is released, as the old one-time release (`repairLegacyTransfersDetailed`, deleted) deleted it, when it is not in a mutually unique pair among the eligible transfer legs (transfer family, not decided not financial, automation-eligible) and it has an intra-bank counterpart (`TransactionMatcher.hasPendingIntraBankCounterpart`) that is also such a released row: two rows for one movement, which only the old per-leg assembler wrote. The released legs are assembled like unlinked legs (a pair if now unique, else a pending match). A single-leg row whose ambiguous counterparts are unlinked or protected is kept (keep rule): today's lone-owned-leg step writes such rows, and they are not double-counted. The scope already reaches every leg this rule reads. After restoring an old backup holding such rows, nothing runs at startup: the next full pass, or a scoped pass for a new message near them, releases them.

With no stored rows the projection is the rebuild from nothing of plan 3.1: a decided row comes back from its posting columns (`LedgerProjector.decidedRow`): a type and a time, the containers as recorded (they may be null) and the zone as recorded, null included.

`LedgerProjector.scope(affected, events, decisions, storedFacts)` returns the raw SMS a projection of `affected` must include so its rows for them equal the full projection's: the legs of every decision or stored row naming one, every installment of the same loan, and every transfer leg reachable through legs whose times may fall within one match window (receipt, SMS-local, or any-zone effective time). `LedgerProjectorPropertyTest` checks determinism, input-order independence and scoped = full on random corpora; `LedgerProjectorTest` covers each rule.

### Rules of the derived ledger

The owner decided these at gate 3.2 (2026-11-12); the projector and the writer follow them, never changed silently.

- **Kept as stored, through the keep rule.** A decided row whose decision lacks its type, time or zone; a row still linking an SMS decided not financial (the comparison may still flag one as `DECIDED_NON_FINANCIAL_BUT_STORED`, for information); a type or amount edited only on the row; a stored row that links no raw SMS; a stored row whose evidence is no longer automation-eligible; a stored row whose card or account is no longer owned; a stored `FEE` for an installment that cannot become a repayment; a stored pair one of whose legs no longer derives; an undecided single-leg self-transfer (also the leg a merged self-transfer is left with) whose stored time no longer matches its evidence. Each stays exactly as stored.
- **Double-posted legs released.** Stored single-leg self-transfers that are each other's ambiguous intra-bank counterparts are released in every pass, so their double-counted money is not kept ("Double-posted legs" above; owner, 2026-11-12). A single-leg row whose ambiguous counterparts are unlinked or protected stays as stored.
- **Re-derived automatic rows.** A row the projection still derives with the stored type and amount takes today's derivation: a filled zone, a re-derived bank time, more container detail (a transfer's destination account, the card used), merchant and counterparty, a derived pair joining a stored external or single-leg self-transfer leg (rule 4b); a stored pair is never split. A stored `FEE` healed into a `LOAN_REPAYMENT` takes the derived type.
- **Fee with another amount.** A stored `FEE` for an installment whose amount differs from the installment's stays exactly as stored (owner rule: no stored amount changes), also through a later user decision on that row: reclassify, ignore and restore act on the row as stored. `LedgerProjectionWriterTest.aStoredFeeWithAnotherAmount_staysAFee`.
- **Ids.** A derived row's id always comes from its raw SMS set (`TransactionIdFactory.fromRawSmsIds`), so a re-paired self-transfer gets a new id.
- **Re-keyed commitment.** When a row is replaced (a regrouping, a re-derived id, the release of a merged row), the writer moves its commitment to the row that replaced it, so `commitment.sourceTransactionId` always resolves.
- **Exchange rate.** Only a complete stored pair on the same raw SMS set is carried to a derived row by the projector; the writer also keeps a half pair there, for enrichment to repair. A changed raw SMS set has none until enrichment runs.
- **Loan pre-pass.** An eligible installment paid from an owned account confirms an unknown loan before any row is derived, so every installment of the run sees it.
- **Write guard.** The projector has no `AutomaticInterpretationWriteGuard`; the writer applies it to every row it posts or changes ("Ledger writer").
- **Order.** Conflicting decisions (a superseded pair decision naming an SMS another decision posts) are resolved in event-id order.
- **Scope.** The projector scope is the whole chain of reachable legs and stored rows, so a scoped result never differs from the full one (`LedgerProjectorPropertyTest`; through Room, `LedgerWriterScopeTest`).
- **Missing raw SMS.** Evidence without a raw SMS cannot be expressed (the caller leaves it out); a stored row linking it is kept by the keep rule.
- **Rebuild from nothing.** A decision without a zone rebuilds its row without one, as stored. What exists only as a stored row, which the writer therefore never drops (the keep rule holds it), is listed under "Ledger writer".
- **Counts.** The writer maps the projection onto `ReconciliationSummary` ("Ledger writer", Report); only `failed > 0` and the failed ids are read.

### Shadow comparison (plan 3.2)

`application/transaction/LedgerInputLoader` reads the projector's inputs from the database, read-only: the effective parsed events (`EffectiveParsedEventProvider`) with their raw SMS receipt time, `UserDecisionRepository.effectiveFor`, ownership of every referenced account, card and loan through `OwnershipResolver`, `StoredFacts` (the stored rows with their linked raw SMS, and the `USER_CORRECTION` closures), and the zone. It also returns the review items. `ReviewContainer.ledgerInputLoader` builds it for the debug export; the writer builds its own over the same repositories.

`LedgerShadowComparison.compare(inputs)` is pure. It joins old and new rows that share a raw SMS: equal raw SMS sets compare field by field (`CHANGED`), a group with no row on one side is `ONLY_OLD` or `ONLY_NEW`, anything else is `GROUPING`. Each difference gets the first reason whose condition is checked on the inputs, else `UNEXPLAINED`; a reason names what was checked, never a cause it cannot see (pattern or inference freshness, pass order). In order: `STORED_ROW_WITHOUT_LINKS`; `RAW_SMS_WITHOUT_EVIDENCE` (only when the group has no projected row and every stored row links a raw SMS with no effective parsed event); `ID_ONLY` and `EXCHANGE_RATE` (Rules of the derived ledger: Ids, Exchange rate); `ZONE_FILLED` (only the zone differs and the stored zone is empty); `TIME_REDERIVED` (only the time differs, the stored zone empty or equal; the block prints the shift in seconds); for an SMS with an outcome decision `DECIDED_NON_FINANCIAL_BUT_STORED`, `DECIDED_WITHOUT_POSTING_COLUMNS` (a posting kind lacking type or time) or `DECIDED_ROW_NOT_STORED` (cause not determined); then `INSTALLMENT_FEE` (a stored FEE for an installment the projector does not post), `OWNERSHIP_NOT_OWNED` (a dropped row none of whose registry containers is OWNED now), `STALE_EVIDENCE` (the evidence's amount or merchant differs, or it is no longer automation-eligible) and `ROW_TYPE_WITHOUT_DECISION` (the type differs and there is no outcome decision). It also compares the open review kind per raw SMS as the store would apply it: the stored `REQUIRED` review against the projection's candidate, where a candidate on a `RESOLVED` review stays resolved (`ReviewItemDao` never reopens one) and is only counted. It lists the projection's retry ids. Settled ids are not compared: they are the complement of rows and reviews.

`LedgerShadowComparison.report(inputs)` is plain text: per reason the count and the summed amounts per currency of the old and new rows, then one block per difference with both rows. Ids are masked with `AppLogFormatting.maskId` plus a short digest (a digest of a guessable id can be matched, so the report stays private); in containers, merchants and counterparties any five or more digits, also grouped by spaces or dashes, keep only the last four; no SMS text.

- **Corpus (CI).** `LedgerShadowCorpusTest` builds the snapshot corpus as `LedgerSnapshotTest` does (legacy states after a full pass), fills `user_decision` from the directly seeded review rows (test support `UserDecisionBackfill`), writes `app/build/reports/shadow-ledger/shadow_comparison.txt` (uploaded with the unit-test reports) and asserts it equals `testdata/ledger_snapshot/shadow_comparison.expected.txt`. After the owner's decisions of 2026-11-12 (the keep rule and the rules of the derived ledger above) it holds no row or review difference; a new difference fails CI and goes to the owner. Regenerate only with `MASROOF_UPDATE_SHADOW_COMPARISON=1 ./gradlew :app:testDebugUnitTest --tests '*LedgerShadowCorpusTest' --rerun`.
- **Owner decisions, 2026-11-12.** Rows the user decided, and stored rows the projection no longer derives (evidence no longer eligible, card or account no longer owned, an installment fee, a pair with one leg left), stay exactly as stored: rejected as differences and kept by the keep rule. Rows the projection still derives take today's derivation (zone, time, container detail, pair ids): accepted.
- **Device (debug builds).** Settings → Logs shows "Export ledger comparison" only when `BuildConfig.DEBUG`; it writes the report of the device database to a document the user picks.
- **Any database file.** In a Robolectric test, open the file with `MasroofDatabase.builder(context, MasroofDatabase::class.java, name)`, build a `LedgerInputLoader` over its Room repositories (as `DashboardLedgerWorld.ledgerInputLoader` does) and call `LedgerShadowComparison.report(loader.load())`. Since the switch the stored ledger was written by the writer, so this compares it with a fresh projection.

### Ledger writer (plan 3.3a)

Gate, approved by the owner on 2026-11-12. Every entry point of `TransactionReconciliationService` projects and writes through `LedgerProjectionWriter`; the callers (live processing, the historical batch, review workflow, restore, reclassify, ignore, ownership changes, model refresh) are unchanged and read the report as before; what the report holds is under "Report" below.

| Entry point | Projection |
|---|---|
| `reconcileStoredEventsDetailed`, `reconcileStoredEvents` | `LedgerInputLoader.load()`: the whole history |
| `reconcileAffectedRawSmsIds(ids)` | `LedgerInputLoader.loadScope(ids)`: `LedgerProjector.scope` around the ids, grown from the raw SMS, decisions and stored rows it names, the installments of the same loan (`listRawSmsIdsReferencingLoan`) and the transfer candidates of `LedgerProjector.pairingWindow` (`ParsedEventRepository.listTransferCandidates`, one bounded query per reached transfer, parsed or corrected family), until nothing new is reachable; no whole-history read, except that more than 200 affected raw SMS (a historical batch), or a chain needing more than 12 rounds (transfers whose times keep touching), is read whole once and narrowed in memory. The scope is closed, so its rows equal the full pass's (`LedgerProjectorPropertyTest`). The rows of the whole scope are written; the report covers only the affected raw SMS and the units written (see "Report") |

**Write.** Stored and projected rows of the scope are grouped into units, the rows that share a raw SMS or an id; a unit whose rows are equal (parsed-event ids compared as sets) is not touched. All units are written in one transaction (`inTransaction`, production `StorageContainer.withDatabaseTransaction`). Inside it, before anything is written, for each unit:

1. Its current rows are re-read: equal to the projection, nothing to do; different from what was projected from, the whole pass projects again once, then the units are failed.
2. `AutomaticInterpretationWriteGuard` checks the evidence of every raw SMS of the unit's projected rows, for each saved row (removing a row the rules no longer post, the old informational cleanup, is not guarded). A blocked unit stays as stored and is failed and retryable, except that a unit that only changes zone, time, container detail or id of rows on the same raw SMS sets with the same type and amount is kept (stays as stored, not retried), as the old pass never rewrote a posted row; a stored row of one or two raw SMS that an outcome decision or a correction closure protects is kept verbatim by the projector and is in no unit; a row of three or more raw SMS with a protected leg can still lose its unprotected stale legs (rule 3), and that change passes the guard like any other. A decision that arrives meanwhile blocks the unit and fails it, to retry. Kept stored rows are not writes.
3. A unit whose re-keyed commitment would meet another commitment on one row (the source is unique) is kept: written as nothing, never failed, so no commitment ever names a deleted row.

Then each unit unlinks the raw SMS that move, saves its projected rows (`FinancialTransactionRepository.save`, which keeps a complete exchange-rate pair on an existing id) and re-keys. A thrown error rolls the whole write back and the call throws, which callers already treat as incomplete with durable retries. Without a write transaction (test doubles), a save that loses to another writer puts the unit's rows back and fails it, and the pass projects again once if the rows really moved.

**Re-keying, by raw SMS set, in the same transaction.** A stored exchange-rate pair, complete or half, follows its raw SMS set to the projected row (a re-derived pair id keeps it); a changed set has none until enrichment runs. `commitment.sourceTransactionId` and `review_item.resolvedTransactionId` of a removed row move to the projected row linking its first still-linked raw SMS; a review whose raw SMS is in another projected row moves to that row (`FinancialTransactionRepository.moveTransactionReferences`). A row removed with nothing replacing it keeps its references, as before. No other table or preference stores a transaction id; `analysis_alert.evidenceId` in `analytics.db` is derived and rebuilt by analysis.

**Report.** A kept unit settles only the raw SMS its stored rows link; any other raw SMS of its projected rows has no row, so it is reported as a review candidate (pending match for a transfer, else needs review), never settled or failed. A full pass reports every raw SMS. A scoped pass reports the affected raw SMS and the raw SMS of the units it wrote: a neighbour unit that failed or was kept is left as stored and not reported (its own pass reports it), as the old scoped pass reported only the affected ids and treated neighbours as candidates. Within that: settled = the projection's settled ids minus failed and minus unwritten ones; failed ids = the affected retry ids plus the raw SMS of failed units that contain an affected id; review candidates = the projection's, plus those of kept units. `assembledSingle` and `matchedPairs` count new rows of written units (one raw SMS, or more), `alreadyLinked` the other settled SMS with a row, `ignored` the settled SMS without one, `pendingMatch` and `needsReview` the candidates, `failed` the failed units plus the other retry ids. `ReconciliationCompletionPolicy` reads it unchanged.

On this path `assembledSingle`/`matchedPairs` are rows newly written (new id or new raw SMS set) with one or more raw SMS, `alreadyLinked` and `ignored` are settled raw SMS with and without a row, and `failed` counts failed units plus retry ids.
`pendingMatch`/`needsReview` are the review candidates left at the end of the pass (not every pending outcome on the way); only `failed` is read in production. Loans the pre-pass confirms are recorded through `OwnershipConfirmationService` before the write.

**Rebuild from nothing.** `LedgerRebuildCorpusTest` empties the ledger of every snapshot corpus world and rebuilds identical rows and commitment links (exchange-rate pairs return with enrichment). What exists only as a stored row, and is never dropped because the keep rule holds it: a row whose evidence is no longer automation-eligible, whose container is no longer owned, that links no raw SMS, or whose type or amount was edited only on the row; a `FEE` for an installment that cannot become a repayment (in the corpus: `legacy-fee-no-loan`); a pair one of whose legs no longer derives; a row kept because a raw SMS of it was decided not financial (nothing derives it);
a decided row whose decision lacks its type or time, whose leg has no parsed event or no positive amount, whose pair decision names one leg only, or whose id is not the id of its raw SMS set.

**Any database file.** In a Robolectric test, open the file with `MasroofDatabase.builder(context, MasroofDatabase::class.java, name)`, build a `TransactionReconciliationService` over its Room repositories as `DashboardLedgerWorld.reconciliation` does, passing `inTransaction = { db.withTransaction { it() } }`, and call `reconcileStoredEventsDetailed()`.

## Processing and authority

Live SMS is captured durably before WorkManager receives a raw SMS id. Historical intake parses a batch before one discovery/reconciliation/review pass. Validation, confirmed ownership and manual decisions retain financial authority. Informational/OTP messages never post. Reconciliation failures retain durable retries.

The engine uses rules first. Eligible local family inference never supplies an amount, ownership or approval. Explicit corrections feed local learning. Rule/model refreshes target unresolved, unposted evidence without a manual decision. Previously posted history is preserved.

Message learning is "remember my decision", per bank, for every configured bank. Each bank's exact-template memory is built only from that bank's human decisions; there is no statistical message model. A remembered "not financial" decision settles a later message with the same template that the rules could not identify, in any bank, and `InformationalMessagePolicy` keeps it out of review. A remembered financial family is used only where the bank's role rules ask for one; it never supplies money, ownership or posting. Conflicting decisions for one template propose nothing. Banks with role rules re-read their pending messages when the rules or their decisions change; banks without role rules refresh only the pending messages a learning change would change, so taught patterns are still applied explicitly. Banks the user adds learn the same way.

## Data and recovery

`masroof.db` is schema version 1 (`MasroofDatabase.VERSION`). The owner restarted the numbering on 2026-11-13: the app was still in development and personal use, so the earlier versions (the v18 baseline and v22) and their upgrade path were deleted, and the owner's own data was converted once, outside the app. Version 1 holds the tables v22 held. `MasroofDatabase.builder` (module `data`) is the only builder for the live file; `MasroofDatabase.MIGRATIONS` is empty and a later schema change adds an additive, tested step to it (1→2, with a migration test from the committed version 1 fixture, a device upgrade test, and version 1 archives still importing). A file at any other version, the retired 18 and 22 included, or with an unknown identity hash is rejected by Room and stays byte-identical (`MasroofMigrationOpenPathTest`, `MigrationOpenPathDeviceTest`); the same tests upgrade the fixture through a test-only next version with every row intact. Never a destructive fallback. Already queued work of retired workers is cancelled once at startup (`RetiredWorkCleanup`). Existing preference files, Keystore alias, app id and worker names remain stable. `learning.db` stores labels/provenance; `analytics.db` stores advisory results and preferences; both are version 1 too. Both are derived and open through `PreservingOpenHelperFactory`, so the framework's corruption handler never deletes them; `LearningStore` reports a corrupt `learning.db` to its caller (`BankInterpretationService` logs it under `ML`), deletes the cache itself (`invalidate`) and rethrows, and the next use opens an empty file that learning refills from the user's decisions (`LearningStoreCorruptionTest`; parsing and posting never wait on it, `ReplicaMachineLearningTest`). Old Lab files are neither imported nor deleted automatically.

### Processing retry (`processing_retry`)

One row per raw SMS whose derived pass (ownership discovery, reconciliation, review update) still has to run: the ids still to project. Since the ledger writer (plan 3.3a) a unit that failed is not written while the units the same pass wrote stay, and a rerun is idempotent, so recovery only reruns the pass for the listed ids (a batch set lists every id of the batch, also those whose units posted); nothing records a stage or partial progress (plan 3.4). Columns: `rawSmsId`, `createdAtEpochMillis`, `mode`. `mode` is who reruns the pass and is persisted, so both names stay readable forever:

- `LIVE`: written by `ProcessingRecovery.markExhausted` when `LiveSmsProcessingWorker` runs out of attempts, with a `processing_error` review unless the review is already resolved. `LiveSmsIntake.schedulePendingProcessing` hands each id back to the per-message worker at startup; a later complete live pass clears the row.
- `HISTORICAL_BATCH`: written by `ProcessingRecovery.retryLater` (an incomplete historical batch, interactive reparse, correction, restore, ownership change or full `refreshReviewQueue`), which then enqueues the one `HistoricalDerivedRecoveryWorker` (unique work `historical-derived-recovery`, `KEEP`); startup enqueues it too. Parse paths that must survive process death write it in their parse transaction (`ParsedEventRepository.saveForHistoricalBatch`, `ModelRefreshWorkflow`). `HistoricalDerivedRecovery` runs one pass over the set and clears it only when `ReconciliationCompletionPolicy` reads the report as complete and the review update succeeded. The key is the raw SMS id, so a row has one mode; a later write replaces it, and startup leaves a `HISTORICAL_BATCH` id to the batch pass even if it also has a `processing_error` review.

An SMS whose latest outcome decision posts nothing is never written and never listed (`RETRY_NOT_DECIDED_NON_FINANCIAL`). Every version since the column was added (schema v15) wrote only these two enum names, the shrink in plan 3.4 changed no stored value.

### User decisions (`user_decision`)

`user_decision` is one append-only table (plans 2.1 and 5.2). Every explicit user decision is appended there in the same database transaction as the existing write it describes; the existing stores (`review_item`, `financial_transaction`) are written exactly as before. A bank choice has no other store either (the `user_selected_bank:` reason prefix went with the restart at version 1). A correction has no other store: its `CORRECTION` row is the only record (the `user_correction` table was dropped at the restart at version 1). Since plan piece 2.3, processing reads decisions from this table only ("Reading decisions" below); the legacy stores are still written so screens and the kept reads keep working. `UserDecisionDao` only inserts and reads; it has no UPDATE or DELETE. A later row for the same subject supersedes an earlier one by `sequence`.

| Column | Meaning |
|---|---|
| `sequence` | `INTEGER PRIMARY KEY AUTOINCREMENT`: the append order, never reused. The latest decision for a subject is the one with the highest `sequence`, not the latest clock time. |
| `rawSmsId` | The subject: the raw SMS the decision is about (the outgoing leg for a pair). Raw SMS ids are stable; transaction ids are not, because reconciliation may recreate a row. Foreign key to `raw_sms` (delete `RESTRICT`, update `CASCADE`). Null exactly for a `RECURRING` row, which names a series, not an SMS; `UserDecisionDao.insert` refuses any other combination. |
| `pairedRawSmsId` | The incoming leg of a self-transfer pair, else null. Same foreign key. |
| `kind` | A `UserDecisionKind` name (below). |
| `decidedAtEpochMillis` | The injected clock's instant at the decision (the same instant the existing write stores). |
| `transactionType` | The `FinancialTransactionType` the user chose (or that the chosen action posts), for `FINANCIAL_TYPE`, `EXTERNAL_TRANSFER`, `SELF_TRANSFER_PAIR`, `RECLASSIFY`. |
| `sourceContainerId`, `destinationContainerId` | The endpoints the decision posted with, for the same kinds. They are derived from evidence and ownership at decision time, so 2.3 can reproduce the user's row without reading it back. |
| `occurredAtEpochMillis`, `occurredAtZone` | The effective occurred-at instant (`TransactionTiming.effectiveOccurredAt`) and zone exactly as posted on the `financial_transaction` row, for the same kinds. `RESTORE` is appended before reconciliation posts, so it has none; a type chosen on restore adds a `RECLASSIFY` row that has them. The amount is not copied: it comes from the evidence or a correction. |
| `messageFamily`, `amountDecimal`, `amountCurrency`, `merchant`, `counterparty` | A `CORRECTION`'s fields (family name, `BigDecimal.toPlainString()`, currency name). The user's own text, never SMS body text. |
| `bankId` | The bank of a `BANK_SELECTION`. |
| `subjectKey`, `answer`, `payload` | A `RECURRING` row only (plan 5.2): the series key, the answer (`CONFIRMED`, `CONFIRM_PENDING`, `NOT_RECURRING`, `DISMISSED` or `UNDONE`) and the `RecurringFeedbackPayload` JSON exactly as written (null for `UNDONE`). See "Recurring answers" below. |

Unused columns are null. Indices: `(rawSmsId, sequence)` for "latest decision for this SMS", `(pairedRawSmsId)` for the second leg of a pair.

| Kind | Writer and call site | Existing write it joins |
|---|---|---|
| `FINANCIAL_TYPE` | `ReviewWorkflowService.resolveAsFinancialType` → `RoomManualReviewResolutionRepository.persistSingleResolution` | posted row + review `USER_FINANCIAL_TYPE` |
| `EXTERNAL_TRANSFER` | `ReviewWorkflowService.resolveTransferAsExternal` → `RoomManualReviewResolutionRepository.persistSingleResolution` | posted row + review `USER_EXTERNAL_TRANSFER` |
| `SELF_TRANSFER_PAIR` | `ReviewWorkflowService.resolveSelfTransferPair` → `RoomManualReviewResolutionRepository.persistPairResolution` | posted row + both reviews `USER_SELF_TRANSFER_PAIR` |
| `NON_FINANCIAL` | `ReviewWorkflowService.resolveAsNonFinancial` | review `USER_NON_FINANCIAL` |
| `CORRECTION` | `ReviewWorkflowService.applyCorrection` | none: the decision row is the correction (the later `USER_CORRECTION` review closure is its consequence, not a second decision) |
| `RECLASSIFY` | `TransactionReclassificationService.reclassify` → `RoomTransactionReclassificationRepository.updateWithDecision` | row type/endpoints + review `USER_FINANCIAL_TYPE` |
| `IGNORE` | `TransactionIgnoreService` → `RoomTransactionIgnoreRepository.ignoreSingle` | row deleted + review `USER_NON_FINANCIAL` |
| `RESTORE` | `TransactionRestoreService.restore` | review `USER_FINANCIAL_TYPE` (a chosen type adds its own `RECLASSIFY` row) |
| `RESTORE_REVERTED` | `TransactionRestoreService.restore` when nothing could post and the restore is undone | review back to `USER_NON_FINANCIAL` |
| `BANK_SELECTION` | `ExplicitBankSelectionWorkflow.selectAndReparse` | none: the decision row is the choice; `ReviewDetailLoader` reads it for the screen |
| `RECURRING` | `AnalyticsWorkflow` (reject, accept, undo, the pending promotion) → `RecurringAnswerStore` | the candidate's advisory status in `analytics.db`, written after the answer (inside the transaction only for undo) |

Application services join through `UserDecisionRepository.recordWith(decision, before) { write }`, which reads the current row, runs the write and appends in one Room transaction under the access gate, and appends only when the write returns a result that differs from the row before it; the Room repositories append through `UserDecisionDao` inside their own transaction. An idempotent repeat (a double submit, the same bank chosen again) changes nothing and appends nothing. A failure at the append rolls the existing write back too.

#### Rows from before the dual write

The owner's data predates the dual write. It was converted once, outside the app, before the restart at version 1; that conversion filled `user_decision` under the rule the owner approved on 2026-11-12: only resolved user reviews, corrections and bank selections became decisions, and a posted transaction with neither is automatic (no row), even if its type was edited without leaving a review. The app holds no backfill. `UserDecisionBackfill` (test fixtures of `:data`) is the same SQL, kept only so tests can seed decisions from review rows they write directly.

#### Reading decisions (plan 2.3)

Processing reads user decisions through `UserDecisionRepository.effectiveFor(rawSmsIds)` (`RoomUserDecisionRepository`, `UserDecisionDao.listNaming`, chunked under the bind limit). It folds the rows that name each raw SMS, in `sequence` order, into `EffectiveUserDecisions`, three independent layers:

| Layer | Rows | Value |
|---|---|---|
| `outcome` | `FINANCIAL_TYPE`, `EXTERNAL_TRANSFER`, `SELF_TRANSFER_PAIR`, `NON_FINANCIAL`, `RECLASSIFY`, `IGNORE`, `RESTORE`, `RESTORE_REVERTED` naming the SMS as `rawSmsId` or `pairedRawSmsId` | the latest one; outcomes supersede one another |
| `corrections` | every `CORRECTION` of the SMS | all of them, oldest `decidedAtEpochMillis` first, equal times in `sequence` order; `EffectiveParsedEventProvider` overlays them field by field (later wins) |
| `selectedBankId` | `BANK_SELECTION` | the bank of the latest one |

A `RECURRING` row is in none of the layers: it names no raw SMS (`listNaming` never returns it), `EffectiveUserDecisions.of` and `LATEST_OUTCOME_KIND_SQL` exclude the kind, so it never changes a posting outcome (`UserDecisionReaderTest.aRecurringAnswer_isNoDecisionOfAnySms`).

`isNonFinancial` (latest outcome `NON_FINANCIAL`, `IGNORE` or `RESTORE_REVERTED`) means what a resolved `USER_NON_FINANCIAL` review meant: post nothing. `confirmsFinancial` (latest outcome `FINANCIAL_TYPE`, `RECLASSIFY` or `RESTORE`) means what a `USER_FINANCIAL_TYPE` review meant: the SMS is financial, so the posting gate is lifted. `outcome?.kind == EXTERNAL_TRANSFER` is `USER_EXTERNAL_TRANSFER`, and any outcome is a decision reconciliation must not undo. A correction never changes the outcome: a correction needs a `REQUIRED` review, so an ignored SMS cannot be corrected and stays non-financial (`ManualDecisionReplayTest.correctionOfAnIgnoredSms_isRejected_andItStaysNonFinancial`). SQL that filters in the query (`ProcessingRetryDao` retry sets, `ParsedEventDao.markHistoricalIntent`) uses the same "latest outcome posts nothing" rule (`LATEST_OUTCOME_KIND_SQL`).

Why the store equals the old reads: every writer that sets a review's user resolution appends the matching row in the same transaction (dual write, 2.1), the owner's converted data was backfilled under the 2.2 rule (only resolved user reviews, corrections and bank selections became decisions) before the restart at version 1, and nothing else changes a user resolution (`ReviewQueueUpdater` and `reopenAutomatic` only touch automatic settlements; `markResolvedAtomic` only lets an ignore override, or a restore leave, a resolved review). The one known difference is a race: a manual resolution whose row an automatic pass posted with the same id between the screen's check and the write keeps the review `AUTO_NO_LONGER_REQUIRED` while the store has the decision; the store then protects the row the user saw. After that race, `ModelRefreshWorkflow` can also `reopenAutomatic` that review to `REQUIRED` while the store still holds the decision, so the queue shows the SMS for review again although processing treats it as decided.

| Read | Decision source now |
|---|---|
| `TransactionReconciliationService`: skip a non-financial SMS, keep an external transfer, lift the gate for a financial decision, `preservesExplicitUserDecision`, transfer candidates | `outcome` |
| `TransactionReconciliationService`: an SMS with a posting decision (`UserDecision.posting` columns) and no posted row | rebuilds the user's row from the decision and the effective evidence (`LedgerProjector.decidedRow`); a pair rebuilds one movement for both legs. Only an emptied ledger reaches it: every path that removes a decided row appends a superseding outcome. A rebuilt external transfer or pair still passes the automatic write guard: with stale pattern or inference evidence it is rejected on every pass and stays incomplete |
| `AutomaticInterpretationWriteGuard` (protected posted SMS, financial decision) | `outcome` |
| `ProcessingRecovery`, `ProcessingRetryDao`, `ParsedEventDao.markHistoricalIntent`, `PatternEvidenceProtection` (non-financial) | `isNonFinancial` |
| `ProcessStoredSmsUseCase` reprocessing order, `PendingPatternsWorkflow` bank | `selectedBankId` |
| `EffectiveParsedEventProvider`, `PatternEvidenceProtection`, `PendingPatternsWorkflow`, `BankInterpretationService.mayRefresh` (corrections) | `corrections` |

Reads kept on the legacy stores, each allowed by name in `DecisionReadBoundaryTest`:

- `ReviewCorrectionClosure`: a correction whose own pass posted the SMS closes its review as `USER_CORRECTION`, and that closure protects the posted row (reconciliation, the write guard, `mayRefresh`). The store holds the correction but not whether that pass posted; protecting every posted corrected SMS would change what reconciliation keeps for a correction posted only by a later pass. That difference belongs to the plan 3.2 shadow comparison.
- `TransactionRestoreService` checks that the review is ignored before it rewrites that same review (`markResolvedAtomic` lets only an ignored review be restored); it is a legacy-store writer.
- `BankInterpretationService` learning feedback keys a review decision by its review id (a correction by its `sequence`, from `listCorrections`) and reads the resolved row's current type; switching would change the learned decisions and their fingerprint.
- `PatternEvidenceProtection` treats any settled review, automatic included, as evidence to keep, and `PendingPatternsWorkflow` skips a `REQUIRED` review with a resolution: queue state, not a user decision.
- Screens (`ReviewPresentationMapper`, `ReviewReasonLabels`, `ReviewDetailLoader`, `InterpretationProvenanceWorkflow`) display the review.

#### Recurring answers (plan 5.2)

The user's answers about recurring-payment series are kept in `user_decision` itself (owner, 2026-11-12), append-only, as `RECURRING` rows (`subjectKey` = series key, `answer` = the answer kind, `payload` and `decidedAtEpochMillis` as written). `RecurringAnswerStore` (`data/repository`) is the only reader and writer, through `DatabaseAccessGate`, each write one `masroof.db` transaction:

- The answer in force for a series is its latest row (`listRecurring`, ordered by series then `sequence`); a series whose latest row is `UNDONE` behaves as never answered. `effective()` returns the answers in force as `RecurringFeedbackRow`s ordered by series key, which `RecurringDecisionWorkflow.build`, the corrections list and the analysis fingerprint read.
- Reject, accept and the pending save append an answer; undo appends `UNDONE` when the answer in force is `NOT_RECURRING` or `DISMISSED`; the `CONFIRM_PENDING` → `CONFIRMED` promotion (`RecurringDecisionWorkflow.promotePending`) appends `CONFIRMED` with the pending answer's payload and `decidedAt`. The answer is written first and the candidate's advisory status in `analytics.db` after it; a failed status write leaves the answer, which the next refresh applies. Undo writes the status inside its `masroof.db` transaction.

**Backups.** Exports carry the answers inside `masroof.db`; the analytics archive holds none. A failed or rolled-back restore puts back the original database, which holds the original answers.

Known limits, for plan 3.2: a posted type or endpoint edited without a review or correction has no decision (owner rule of 2.2). Reconciliation keeps it only because the row stays posted; an emptied ledger rebuilds it from the evidence (`ManualDecisionReplayTest.typeEditedOnlyOnTheRow_isKeptWhilePosted_andAutomaticOnceTheLedgerIsEmptied`). A rebuilt row has no exchange-rate pair until enrichment runs again, and a backfilled pair whose outgoing leg was unknown takes the counterparty of its subject leg first. `ManualDecisionReplayTest` empties the ledger and replays for every outcome kind and a correction; a bank selection is a reparse input and does not depend on the ledger.

The learning store is schema 2 and the analytics store is schema 4; both open through `PreservingOpenHelperFactory` (above). Posted type edits atomically update the financial transaction and its latest `USER_FINANCIAL_TYPE` review decision, and append a `RECLASSIFY` row to `user_decision`. Message learning rebuilds from durable decisions before enabling personal assistance; no derived message-model cache is written or loaded. Exact template suppressions include bank and feature identity and revoke human-template assistance. Financial `EXPENSE`/`INCOME`, ownership decisions and field-only edits do not invent family labels. Unsupported or ambiguous decisions have an explicit feedback disposition.

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

Patterns in backups (plan 5.1): the pattern document travels only inside the database; export fails when the stored document does not decode, and every imported row must decode before the live file is replaced. A rollback restores the parked original database, and with it the original patterns. The restore journal carries no pattern file.

Backup format: `BackupPackageFormat.FORMAT_VERSION` is 1 (restarted with the schema, owner 2026-11-13). `BackupPackageCodec.validateManifest` accepts only that format with a known (version, identity hash) pair; anything else is an invalid package before the live database is touched. Archives written before the restart (schema v18 or v22) are refused.

Encrypted backups retain the existing envelope. A restore accepts only a known financial (version, identity hash) pair (`BackupSchema.PRODUCTION`: the current version and `MasroofDatabase.IDENTITY_HASH`, checked against the exported schema); anything else is an invalid package and the live database is not touched. Once a later version exists, a backup of an earlier known version migrates on its staged copy through `MasroofDatabase.builder` with the preserving open helper, while the live database is still open, before the restore journal is written and the live file is replaced; a failed migration leaves the live files, preferences and journal unchanged (`BackupOlderSchemaImportTest`, `BackupMigrationDeviceTest`, with a test-only next version). Export always writes the current version and hash. Optional analysis is included in the durable restore journal for commit/rollback. Restoring an older backup clears stale analysis and rebuilds available results. Learning state is invalidated and reconstructed from retained human decisions.

See [AGENTS.md](../AGENTS.md) for enforced boundaries and [ANALYTICS.md](ANALYTICS.md) for model and alert policy.

### Pattern configuration (`pattern_configuration`)

Plan 5.1, design approved by the owner on 2026-11-12. The message-pattern document (the `MessagePatternConfigurationCodec` text, revisions, deleted ids and role profiles included) is one row of `masroof.db`: `pattern_configuration(id INTEGER NOT NULL, generation INTEGER NOT NULL, document TEXT NOT NULL, PRIMARY KEY(id))`, id always 1, `generation` repeating the document's own. No row means an empty configuration.

`MessagePatternStore` keeps its API. Each write (`saveBank`, `deletePattern`, `installMissingBanks`, `restoreRaw`) reads, checks the expected generation and replaces the row in one database transaction; the generation still grows by one per write, and the size cap, codec validation and revision rules are unchanged. No Kotlin lock is held around database access, so a read from inside the financial write transaction (`PatternInterpretationWritePolicy`, through `AutomaticInterpretationWriteGuard`) runs on that transaction's connection and never waits on a writer. The decoded document is cached per process keyed by the stored generation and text, so another database, a restore or another writer is always seen. A row whose generation column disagrees with its document is refused like a corrupt one. The store uses plain SQL on the open connection, not a DAO, because the open callback has no DAO yet; every touch holds a `DatabaseAccessGate.withBlockingAccess` admission (immediate inside a lease of the gate, also on a Room transaction thread), and retirement waits for admitted ones, so a read or write after a restore or close retired the gate throws `DatabaseRestartRequiredException` (handled like a repository call) and never reopens the file. Room's change tracking does not observe the table.

The live database's Room `onOpen` callback (`StorageContainer`) seeds the bundled banks before the open returns, so no reader sees the patterns before that is done.

### Closing SQLite-backed services

Application shutdown stops producers and drains admitted startup/database work before closing Room. Never cancel a first-open Room continuation and assume SQLite has stopped: its connection callback can still own the open-helper monitor while invalidation setup requests the Room close read lock. Taking Room's close write lock at that point deadlocks. Startup, learning/audit and analytics drain normally; the database access gate also drains independent WorkManager leases. `StartupQueryShutdownTest` controls that native first-open boundary.

## Recurring suggestions and forecast replay

Recurring suggestions come from rules only; there is no recurrence model. The user's
answers are `RECURRING` rows of `user_decision` (see "Recurring answers"), including an
intent recorded before the financial save; a later pass completes it only when the saved
commitment's source and cadence agree. Feedback failure never undoes a saved commitment. Legacy rejection stays a
dismissal; explicit "not recurring" decisions stay until undone. The analysis
archive is version 1.

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
