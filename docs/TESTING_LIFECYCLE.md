# Application lifecycle test contracts

This is a map of existing production paths and executable coverage, not a claim that tests prove every possible bank message or device safe. All tests run on PR/main CI unless the API-specific exception in CI.md applies.

| Boundary | Required evidence |
| --- | --- |
| Pure financial policy extraction -> existing financial writes | `finance-core` policy/compatibility/ownership tests, module boundary tests, `FinancialSchemaCompatibilityTest`, app transaction/statement/dashboard integration tests and the existing real APK update journey |
| SMS capture -> durable raw evidence -> parse -> reconciliation -> review | Bank adapter contracts/fixtures, parse validator tests, historical/live retry unit tests, and real process-death phases at each boundary |
| Reconciliation failures remain incomplete and recoverable | `HistoricalDerivedRecoveryTest`, processing retry repository/workflow tests, native retry/process-death journeys |
| Manual decisions survive refresh and restart | `ModelRefreshWorkflowTest`, learning persistence/device tests, `LearningProcessDeathJourneyTest` |
| ML assists family only, and unsafe assistance abstains | `FamilyLearningTest`, interpretation device journeys |
| Forecast coverage, published-vs-actual and baseline gate | `AnalysisLifecyclePolicyTest`, pure analytics policy tests and advisory SQLite migration tests; estimator algorithms are unchanged |
| Commitment identity, changed amount, financial period | `CommitmentPaymentAttributionTest`, `CommitmentsOverviewBuilderTest`, `DashboardFixtureCharacterizationTest`, `DashboardViewModelTest`, `CommitmentPeriodLifecycleTest` |
| Current period rolls forward while explicit history stays selected | Moving-clock ViewModel tests; application period workflow remains the only period authority |
| UI draft/apply/discard and language/theme geometry | Four independent render smoke variants and four chart interaction variants, existing sheet/geometry tests; assertions and pages/scopes retained |
| Financial schema open path (version 1, a later step, rejection) | `FinancialSchemaCompatibilityTest`, `MasroofMigrationOpenPathTest`, `MigrationOpenPathDeviceTest` on the committed version 1 fixture (opens as it is, upgrades through the test-only next step; any other version or hash is refused) |
| User decisions recorded with their write (`user_decision`, one row per decision, rollback leaves none) | `UserDecisionDualWriteTest`; `LedgerFixturesTest` (version 1 archives import with their decisions, current export round-trip) |
| Backup of an earlier known schema (known pairs, staged migration, untouched live file on failure) | `BackupOlderSchemaImportTest`, `BackupMigrationDeviceTest` on the committed version 1 archives under a test-only next version |
| Patterns in the database (crash safety, generation check, current round trip, rollback keeps the original) | `MessagePatternStoreTest`, `DatabaseBackupImportTest`, `DatabaseBackupEncryptionTest`, `MessagePatternBackupTest` |
| First SQLite open -> application close | `StartupQueryShutdownTest` uses a real connection callback barrier; storage corruption recovery tests remain unchanged; startup unit teardown now closes the container normally |
| Whole worker/database lease -> restore/close | Model refresh workflow/worker tests, database access/restore recovery tests and native backup/recovery cases |
| CI output -> merge/release authority | `test_ci_device.py`, routing/script tests and `test_verify_ci_status.py`; incomplete/skipped/duplicate/missing case output and partial CI are rejected |

## Root causes corrected

Cancelling startup did not cancel SQLite's native first-open callback. Room close could acquire the close write lock and wait for SQLiteOpenHelper's monitor, while the callback held that monitor and waited for Room's invalidation read lock. The native barrier regression reproduced both sides of that deadlock. Shutdown now stops producers and drains admitted startup, application-scope capture, learning, analytics and financial leases before closing stores; it does not solve the lock inversion by increasing CI timeouts.

Commitment display names are editable labels. Payment matching now uses the source transaction's persisted merchant/counterparty/biller identity, with a unique biller code taking precedence when both sides provide it. Amount changes do not prevent a match. Only eligible positive payments inside the selected period can mark an occurrence paid. Actual paid amount and source payment ID are exposed; future expected amounts and the ledger are not rewritten. Competing commitments remain unpaid with a visible ambiguity reason instead of assigning the first ID. A one-time commitment's date alone is not payment evidence.

No application identity, preference namespace or production worker identity is added or changed by this work. The financial schema is version 1 (owner, 2026-11-13: the numbering restarted and the upgrade path from v18/v22 was deleted); from the handover of the owner's converted data on, it changes only through additive, tested migrations. Any other version or identity hash is rejected, in a file or a backup; a backup of an earlier known version migrates on its staged copy before it replaces the live file. The learning and analytics databases are version 1 too.

Restore also retires queued readers immediately. A reader waiting behind exclusive restore has not started Room work; it must receive `DatabaseRestartRequiredException` when that instance is retired. Waiting until the restore block exits deadlocks when restore first joins an analytics refresh that is itself waiting for that block. `DatabaseAccessGateTest.retirementReleasesQueuedReadersBeforeTheRestoreBlockCanFinish` controls that order, and the native shutdown/backup restore cases cover it through production wiring. Admitted leases still drain normally.

## Reconciliation test split

`TransactionReconciliationServiceTest` keeps posting/matching/idempotence scenarios;
automation/informational authority is in `ReconciliationAuthorityTest`, pairing and
healing in `ReconciliationTransferHealingTest`, persisted time zones in
`ReconciliationTimingTest`. Each test opens a fresh in-memory Room database; no ledger
is shared between scenarios. Pure modules use JUnit Jupiter; Android/Robolectric tests
stay on JUnit 4.

## Retained lifecycle regression requirements

- Historical parsed facts and the durable batch retry intent commit atomically;
  failed derived work remains incomplete even if other rows posted.
- Manual non-financial decisions, exclusive ledger deletion and retry cleanup
  share a transaction. Interrupted writes roll back together.
- Every restored preference commit must succeed before COMMITTED. Retain rollback
  evidence until the database and preferences agree; wipe sensitive staging data
  at discard boundaries, never files still required for recovery.
- Tokens and backup phrases stay out of saved UI state. A picker result arriving
  after process death without its in-memory phrase must show a retry outcome.
- Saved UI state cannot authorize startup financial access. Active imports survive
  activity resume, while provider iteration and parsing stay off the main thread.
- Process-death capture tests join the actual startup job before arming a halt;
  waiting only for blocking maintenance leaves pending-processing scheduling racing
  with capture. Use completion barriers rather than timing sleeps.
