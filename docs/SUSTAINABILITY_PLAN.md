# Masroof sustainability plan

## Context

The code review found that the foundations are sound (SMS kept as evidence, banks as data, exact money, fail-closed behaviour) but that the app is costly to change:

- The posted ledger is mutable state that reconciliation repairs in place (`TransactionReconciliationService.kt`, 1,206 lines). 125 of the last 359 commits are fixes, most of them here.
- A user decision is spread over `review_item`, `user_correction`, a string prefix, and in some cases only the `financial_transaction` row itself.
- 52,000 of 59,000 production lines sit in `app`; the pure modules are small.
- One 997-line `AppContainer`, with seven services taking optional collaborators.
- State is spread over three Room databases, a JSON file and seven preference files.
- Release builds are not minified; lint covers only unused code and size.

Goal: the same app and the same numbers, built so that a change is small, tested in seconds, and cannot silently corrupt the ledger.

## Owner decisions (already taken)

| Topic | Decision |
|---|---|
| Schema | The v18 freeze is lifted. Additive, tested migrations only; no destructive fallback. Decided 2026-11-12: the installed build is v18 and nothing is installed until the plan ends, so there is one migration from v18 to the final version; the intermediate versions v19–v21 existed only in CI and are refused (from piece 5.2). Superseded 2026-11-13: the numbering restarted at version 1 with no upgrade path from v18 or v22 (see Status). |
| Ledger switch-over | New projection runs beside the old path; the owner approves every difference before the old path is deleted. |
| Remote pattern updates | Not in this plan. Only a masked "report unparsed message" export. |
| Wiring | Clean up and split by hand. A DI library is an optional last piece, decided later. |
| Delivery | One pull request per piece, to `main`. No rebases. |
| Parallelism | One piece at a time. |
| Who does the work | Decided 2026-11-13: the orchestrator owns the project and builds pieces itself by default. Agents are used only where splitting really helps, and their cost is weighed (see below). |

## How each piece is executed

1. **Start.** After the previous pull request is merged, start a branch from the latest `main`. Nothing else is in flight, so no piece ever needs a rebase.
2. **Worktree.** The piece is built in its own git worktree under `.claude/worktrees/`.
3. **Who builds it.** The orchestrator builds small and medium pieces itself, and fixes review findings and CI failures itself. An implementer agent builds a piece only when it is large and can be described clearly, or when two independent parts can run in parallel. The agent definitions in `.claude/agents/` are general roles, not tied to this plan; the task brief names the piece. The Model column is guidance for that case (Sonnet for wide routine work, Opus for intricate or high-risk work), not a rule.
4. **Who reviews it.** The orchestrator reviews every piece. An independent Opus review is added only for high-risk pieces: money, the database schema, backup and restore, or concurrency. A broad read-only search may go to an agent when only the conclusion is needed.
5. **Loop (project workflow).** Implement with tests → run tests → simplify → review → fix → rerun, until the review finds no real bug. `/simplify` and `/code-review` with their own agents are for large or high-risk pieces; on a small change the orchestrator simplifies and reviews directly.
6. **Clean-up is part of every piece.** Code, tests, imports, fixtures and string resources made obsolete by the piece are deleted in the same piece. `AGENTS.md`, `CLAUDE.md` and the matching `docs/*.md` are updated in the same piece whenever a rule or module changes.
7. **Deliver.** Push, open one pull request to `main`, watch CI (unit + device shards) and fix failures. Since 2026-11-11 the orchestrator merges once the review finds no real bug and CI is green. The next piece starts only after the merge.
8. **Owner gates.** A piece that changes a number or behaviour the owner sees stops and asks first (marked **Gate**). So does any schema or backup-format change: its design is described to the owner before it is built.

**Where tests run.** Unit tests run locally before each push; device tests run in PR CI.

Shared acceptance criteria, for every piece:

- `./gradlew testDebugUnitTest :bank-engine:test :finance-core:test :ml-assist:test detekt` passes, and PR CI is green including device tests.
- No new detekt baseline entries; no `@Ignore`; no test deleted unless the behaviour it covered was deleted in the same piece or another test now covers it (named in the PR).
- No unused code, imports or resources left behind.
- The ledger snapshot (piece 0.2) is unchanged, unless the piece is a Gate and the owner approved the difference.
- No SMS body, account number or secret in logs.

## Pieces

### Phase 0 — Safety net and house rules

| # | Piece | Model | Own acceptance criteria |
|---|---|---|---|
| 0.0 | **Build environment. Done** (environment configured outside the repo). Propose an environment setup script that installs Android command-line tools, the platform and the Gradle wrapper distribution. | — | `./gradlew :finance-core:test` and `testDebugUnitTest` run in a fresh session. Done. |
| 0.1 | **Rules and agent config.** `AGENTS.md`: schema rule becomes "tested additive migrations", add this workflow. Add `.claude/agents/implementer.md` (Sonnet) and `reviewer.md` (Opus). Align `docs/ARCHITECTURE.md`, `docs/TESTING_LIFECYCLE.md`. | Sonnet | Rules match the owner decisions; both agent definitions load; no code change. |
| 0.2 | **Full ledger snapshot.** Extend the existing golden harness (`GoldenLedgerSupport.kt`, `testdata/golden_ledger/`, `DashboardLedgerWorld`, `IntegrityLedger`) with a serializer that snapshots every `financial_transaction` row and link (id, type, containers, time, zone, FX pair), review items, commitments and ownership over the whole corpus. Add seeds for today's "legacy" states (stale single-leg self-transfer, fee→financing rows) and a test that a scoped reconcile equals a full one. Commit a binary v18 database fixture and a v18 backup archive. | Sonnet | Snapshot committed and fails on any row change; covers all seven banks; scoped = full; fixtures load. |
| 0.3 | **Remove dead code.** `ParsedEventFactsBackfillWorker` and test; `SmsParseGateway` (test-only); the `learning_runtime` prefs clear; the test-only secondary constructor of `ProcessRawSmsUseCase`; tests that only compare against retired implementations (`M0ProductionBaselineTest`, `ReplicaFinancialParityTest`, `NewEngineFixtureTest`/`NewEngineGateTest` with the testFixtures `AlJaziraRuleProfile`, the pre-batch comparison in `HistoricalSmsScannerTest`), each only where 0.2 now covers it; milestone names in comments and test names (P6–P11, M0–M17); baseline declarations without a file in `finance-core`/`ml-assist`. | Sonnet | Each removed symbol has zero references; every deleted test names its replacement; `RetiredWorkCleanup` and legacy-backup import are kept (they protect old installs). |
| 0.4a | **Migration open path. High.** `MasroofDatabase.kt`: add `MIGRATIONS` (empty) and `BASELINE_VERSION = 18`; storage builder (`AppContainer.kt:204`) adds them; add `room-testing`; rewrite `FinancialSchemaCompatibilityTest` to "18.json is byte-frozen, versions contiguous, every step has a migration". `VERSION` stays 18. Reuse the pattern in `AnalyticsDatabase.kt:90-110` and `AdvisoryLifecycleDeviceTest`. Doc updates include the remaining "schema v18 only" statements (`README.md`, `README_AR.md`, `docs/ARCHITECTURE.md` backup section, `docs/CI.md`, `docs/TESTING_LIFECYCLE.md`). | Opus | A test-only v19 database upgrades the committed v18 fixture with data intact (JVM + device); an unknown or newer schema is still rejected without touching the file. |
| 0.4b | **Backup accepts older schemas. High.** `DatabaseBackupService.kt` (`validateManifest` 164/287, `readIdentityHash` 789, `openAndValidate` 752) and `BackupPackageCodec.kt:31` accept any known (version, hash) pair from 18 up and migrate the staged copy, with the preserving factory, before it replaces the live file. Doc updates include the remaining "schema v18 only" statements (`README.md`, `README_AR.md`, `docs/ARCHITECTURE.md` backup section, `docs/CI.md`, `docs/TESTING_LIFECYCLE.md`). | Opus | The committed v18 archive imports under the test-only v19; a failed migration leaves the live database untouched; unknown hashes rejected. |

### Phase 1 — Wiring

| # | Piece | Model | Own acceptance criteria |
|---|---|---|---|
| 1.1 | **No optional collaborators.** Non-null constructor parameters in `ProcessStoredSmsUseCase`, `HistoricalSmsBatchProcessor`, `HistoricalDerivedRecovery`, `ReviewWorkflowService`, `TransactionReconciliationService`, `TransactionRestoreService`, `TransactionReclassificationService`. Tests pass the existing no-op doubles in `testsupport/` (`NoOp*`, `EmptyRepositories`). | Sonnet | No nullable service/repository parameter under `application/`; snapshot identical. |
| 1.2 | **Split `AppContainer`.** Thin root plus per-feature containers (storage, sms, review, dashboard, patterns/learning, backup, update, maintenance). All seven workers get dependencies from the worker factory. ViewModel factories take their feature container. Startup order kept: `DatabaseRestoreRecovery.recover` before Room opens. | Opus review, Sonnet build | `AppContainer.kt` under 200 lines and out of the detekt baseline; no `as MasroofApplication` in workers; `AppContainerFixture` tests and restart device journeys pass. |

### Phase 2 — One home for user decisions

| # | Piece | Model | Own acceptance criteria |
|---|---|---|---|
| 2.1 | **Decision table + dual write. High.** Migration v18→v19 adds append-only `user_decision`. Every decision writer (`ReviewWorkflowService`, `RoomManualReviewResolutionRepository`, `RoomTransactionReclassificationRepository`, `RoomTransactionIgnoreRepository`, `TransactionRestoreService`, `ExplicitBankSelectionWorkflow`) also writes it in the same DB transaction. No backfill yet. | Opus | Migration test from the v18 fixture; each decision kind produces exactly one row; rollback leaves none. |
| 2.2 | **Backfill. High. Gate, approved 2026-11-12.** Migration v19→v20 fills `user_decision` from `review_item` resolutions, the `user_selected_bank:` prefix, `user_correction`, and the type/containers that exist only on `financial_transaction` rows. Rule for owner sign-off: only rows with a resolved review or a correction become decisions; all other rows are treated as automatic. Approved 2026-11-12: decisions come only from resolved user reviews, corrections and bank selections; a posted row with neither is automatic, even if its type was edited without a review (3.2 reports those). | Opus | Owner approves the rule; migration test proves every kind is backfilled; device upgrade test; a consistency test shows decision store and ledger agree on the corpus. |
| 2.3 | **Read decisions from one place. High.** `BankInterpretationService`, reconciliation, restore, reclassify, ignore and review read the decision store. Remove reading decisions back from `financial_transaction.type` and from reason prefixes, with their now-unused code and tests. | Opus | No production read of a user decision outside the store; "decision survives reparse" (`ManualDecisionReplayTest`) passes with the ledger table emptied first; snapshot identical. Reads kept on purpose (`DecisionReadBoundaryTest`): `BankInterpretationService` learning decisions still read reviews, corrections and the row type; moving them changes the learned decisions and what the assistant suggests, so it is an owner decision, taken with piece 5.2. The `USER_CORRECTION` closure and rows whose type exists only on the row go to the 3.2 difference report. The restore precondition, the queue-state checks and the screens stay as they are. |

### Phase 3 — Derived ledger

| # | Piece | Model | Own acceptance criteria |
|---|---|---|---|
| 3.1 | **Pure projector. High.** New pure-JVM module `:ledger` with `LedgerProjector.project(events, decisions, registry, storedFacts)` built on the pure `TransactionAssembler`, `TransactionMatcher`, `TransactionTiming` in `finance-core`. Zone and FX pair are inputs. Loan-ownership auto-confirm becomes a pre-pass that emits ownership facts. Review candidates and retry sets are returned as values. Not wired into the app. | Opus | Passes `verifyPureJvmDependencies`; deterministic and order-independent (property test); covers resolved-review short-circuits, informational auto-ignore, stale self-transfer release, single-leg counterpart, external-pair and fee→financing cases, scoped = full. |
| 3.2 | **Shadow comparison. Gate, decided 2026-11-12.** Run old and new on the corpus and, in debug builds, on the device database; produce a difference report (masked ids, old row, new row, reason). Decided 2026-11-12: decided rows and stored rows the projector no longer derives stay exactly as stored (the projector's keep rule); rows it still derives take today's derivation (zone, time, container detail, pair ids). | Opus | Report produced in CI and exportable from a debug build; **owner accepts or rejects each difference**; accepted ones are recorded in the snapshot. |
| 3.3a | **Switch the writer. High. Gate, approved 2026-11-12.** The owner approved the switch on 2026-11-12. Reconciliation becomes: project the affected window, replace those rows in one DB transaction, with `AutomaticInterpretationWriteGuard` as a precondition. Commitment links and FX pairs are re-keyed by raw-SMS set when a pairing changes. Old path stays behind a debug-only switch. | Opus | Snapshot equals the owner-approved one; emptying and rebuilding the ledger gives identical rows; FX pairs unchanged; `commitment.sourceTransactionId` resolves; process-death device journeys pass. |
| 3.3b | **Delete the repair code.** Stale-link release, `upgradeStale*`, `releaseAmbiguousPostedSelfTransfers`, `FinancialTransactionEvidenceSyncer`, the debug switch, and their tests. | Opus | `TransactionReconciliationService` under 300 lines and out of the baseline; no reference to the removed functions. Note: the one-time ambiguous self-transfer release that `repairLegacyTransfersDetailed` still runs moved unchanged to `LegacySelfTransferRelease.kt` and goes with 3.3c; `FinancialTransactionEvidenceSyncer` stays because reparse-all (`SmsContainer.storedSmsReprocessor`) still fills blank merchants with it, including on rows the ledger keeps as stored. |
| 3.3c | **Delete legacy repair.** `repairLegacyTransfersDetailed`, `TransferIntegrityRepairCoordinator` and their `MaintenancePreferences` keys (key names stay reserved). | Opus | Startup maintenance tests pass; upgrade from the v18 fixture with legacy rows yields the approved snapshot. Owner decision 2026-11-12: deleted with a narrow projector rule; after restoring an old backup such rows are corrected by the next full pass or a nearby new message, not at startup. |
| 3.4 | **Shrink retry and recovery. High.** `ProcessingRecovery`, `HistoricalDerivedRecovery` and completion handling reduce to "these raw SMS ids still need projecting". | Opus | Live and historical failures still retry and surface an error review; `DeterministicProcessingFaultMatrixTest` passes. |

### Phase 4 — Module boundaries

| # | Piece | Model | Own acceptance criteria |
|---|---|---|---|
| 4.1 | **Dashboard calculators to pure JVM.** Move `application/dashboard/*Builder|*Calculator|*Projection` and tests into a pure module. | Sonnet | Moved tests run without Robolectric; `DashboardFixtureCharacterizationTest` identical. |
| 4.2 | **`:data` module.** Room, DAOs, repositories, preference stores. | Sonnet | `app` no longer applies Room/KSP; schemas export from `:data`; device tests pass. |
| 4.3 | **Gradle replaces source-reading rules.** Delete the parts of `PackageDependencyRulesTest` and similar that a module boundary now enforces. Splitting the UI into feature modules is left out: large churn for little gain in a single app. | Sonnet | Each deleted rule is shown to fail the build when violated. |

### Phase 5 — Fewer stores

| # | Piece | Model | Own acceptance criteria |
|---|---|---|---|
| 5.1 | **Patterns into the database. High.** Migration adds one table holding the whole pattern document in a single row (owner, 2026-11-12); one-time import of `message-patterns/definitions.json`, the file deleted only after a verified copy; `MessagePatternStore` keeps its API. | Opus | Imported configuration equals the file (round trip); generation check preserved; old backups containing the file still import. |
| 5.2 | **Recurring decisions. High.** `recurring_feedback` moves into `user_decision` (owner, 2026-11-12). The migration chain is replaced by one migration from v18 to v22 that creates `user_decision` in its final shape (the answers are its `RECURRING` rows), so no table is rebuilt. Analytics and learning stay separate database files (their content is derived); `LearningStore` gains the preserving open factory. | Opus | Recurring decisions survive upgrade (device test); a corrupt learning file no longer risks a destructive reopen; only v18 and the final version are accepted. |
| 5.3 | **Backup format.** Bump `BackupPackageFormat.FORMAT_VERSION` to 2 (owner, 2026-11-13): format 2 goes with a database that holds the patterns and recurring answers, format 1 with a v18 archive, and any other combination is refused; all older formats, including legacy plaintext v1, stay importable. The restore journal no longer carries the old pattern file; a journal left by an older app version still finishes or rolls back with its own (owner, 2026-11-13). | Opus | Every existing backup fixture imports; encryption tests unchanged; backup code shrinks. |

### Phase 6 — Presentation

| # | Piece | Model | Own acceptance criteria |
|---|---|---|---|
| 6.1–6.4 | **Split large ViewModels**, one piece each: Settings, Dashboard, Review, Onboarding. | Sonnet | Each under 300 lines and out of the baseline; existing ViewModel and UI tests pass. |
| 6.5 | **Reactive reads. Gate.** Room `Flow` queries for review list, registry and commitments instead of reload-after-write. Changes when lists refresh, so the owner decides first. | Sonnet | No manual reload after a write in those screens; refresh tests pass. |

### Phase 7 — Release and upkeep

| # | Piece | Model | Own acceptance criteria |
|---|---|---|---|
| 7.1 | **R8 on.** `isMinifyEnabled = true`; the libraries' own keep rules cover serialization, Room and WorkManager, and names are kept (`-dontobfuscate`) because worker class names are stored. CI builds the release; the emulator smoke is manual (`docs/CI.md`). | Sonnet | Release build passes an emulator smoke: SMS in → row on dashboard, backup → restore. |
| 7.2 | **Lint.** Enable detekt default rule sets step by step; burn down the 74-entry baseline. Done 2026-11-13: the bug-finding sets are on (potential-bugs, coroutines, performance, empty-blocks, exceptions); formatting, naming and style stay off (about 40,000 findings, none a defect). Size rules skip tests and Compose functions (reasons in `detekt.yml`). 19 long production methods and classes in backup, review resolution, interpreters and dashboard builders remain in the baseline, waiting for the owner. | Sonnet | Baseline empty, or only Compose screens with a stated reason. |
| 7.3 | **Device tests where the device matters.** Keep SMS receipt, process death, upgrade, backup/restore on the emulator; move the rest to JVM. Update `ci/device-suites.json`, `ci/device-durations.json`, `docs/CI.md`. Done 2026-11-13: three logic journeys moved to JVM (commitment period, forecast lifecycle, financial parity) and two device tests with a full JVM twin removed. The device suite is otherwise screen tests (Compose rule, screenshots, real Activity), which have no JVM runner here, plus the kept categories; the moved tests are about 5% of device time, so the shard count stays at four. | Sonnet | Every moved test has a JVM twin; fewer emulator shards. |
| 7.4 | **Report an unparsed message. Gate.** Masked export from the review screen, no network. Layout described and approved first. | Sonnet | Export has no account/card digits and no amounts; Arabic and English labels. |
| 7.5 | **Closing sweep.** Whole-project review; final pass on `AGENTS.md`, `CLAUDE.md`, `docs/*`, `README*`; remove leftover dead code, tests and imports. Done 2026-11-13: docs checked statement by statement against the code and corrected; no unused production class or function was found (thirteen functions are used only by tests, as test hooks or full-pass entry points, and stay). | Opus | Docs describe the code as it is; no reference to a removed class anywhere. |
| 8 | **Optional: DI library.** Only if the owner asks after 1.2. | — | — |

## Status (2026-11-13)

Pieces 0.0 to 6.4, 7.1 to 7.3 and 7.5 are merged. The rows above are the record of what each piece did; a class named in a row as removed no longer exists, and the schema versions they name (v18 to v22, backup formats 1 and 2) are superseded by the restart below.

Owner decisions of 2026-11-13: 6.5 is skipped; the 19 long production methods and classes stay in the detekt baselines until each changes for another reason; 7.4 is built with the proposed layout (a share sheet from the review detail, every digit masked); piece 8 (DI library) is not asked for.

### Restart at version 1 (owner, 2026-11-13)

The app is still in development and personal use, so the owner restarted the numbering: `masroof.db`, `analytics.db`, `learning.db` and the backup format are version 1, the app carries no upgrade path from v18 or v22, and the owner's own data is converted once outside the app. One pull request per piece:

| Piece | Work |
|---|---|
| R1 | Done (#433). Renumber to version 1; delete `MIGRATION_18_22`, the production backfill, the one-time copies on open (pattern file, recurring answers), the legacy backup format and the v18 fixtures and tests. Same tables, minus the dead `recurring_feedback` in `analytics.db`. |
| R2 | Done. One store for corrections: `user_correction` dropped; `CORRECTION` rows of `user_decision` are the only record. |
| R3 | Bank selection read from `user_decision` only: the `user_selected_bank:` reason prefix goes. `review_item` keeps its resolution columns (they also hold automatic settlements and queue state). |
| R4 | Private, no pull request: convert the owner's fresh export with the last build that reads it (tag `pre-v1-reset`), verify row by row, hand over a version 1 backup. Version 1 is frozen from that day; later changes are migrations. |
| 7.4 | Report an unparsed message. |

## Order

`0.0 → 0.1 → 0.2 → 0.3 → 0.4a → 0.4b → 1.1 → 1.2 → 2.1 → 2.2 → 2.3 → 3.1 → 3.2 → 3.3a → 3.3b → 3.3c → 3.4 → 4.x → 5.x → 6.x → 7.x`

- 0.2 precedes anything that touches money; 0.4 precedes every schema change.
- 2.3 precedes phase 3: the projector cannot rebuild rows whose user-chosen type exists only on the row.
- While phase 2 or 3 waits at an owner gate, pieces 4.2, 6.1–6.4, 7.1 and 7.4 can be pulled forward; they do not touch reconciliation.

## Verification

- Per piece: shared and own acceptance criteria, checked by the orchestrator (and by the reviewer agent for high-risk pieces) before the pull request, then PR CI.
- Money: ledger snapshot and `DashboardFixtureCharacterizationTest` identical except at owner gates.
- Data safety: migration tests from the v18 fixture database, device upgrade test, import of every existing backup fixture.
- End of plan: release-build smoke on the emulator and the 7.5 whole-project review.
