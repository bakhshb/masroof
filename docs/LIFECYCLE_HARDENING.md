# Lifecycle hardening trace

Baseline: main `9ac85bf9627260f9a215256e19a7e2af8f67a368`. Trace recorded before implementation.

## Startup financial gate

`MasroofApplication.onCreate` recovers interrupted restores before constructing the process-local `AppContainer`. `runStartupMaintenance` launches the blocking facts backfill and transfer repair; its deferred result is process-local. `MainActivity.onCreate` composes a gate before `MasroofRoot`, its financial ViewModels, and activity-result launchers. `onResume` separately guards financial refresh with `startupFinancialReady`.

The gate's `rememberSaveable` result can restore READY from an old process before `LaunchedEffect` awaits this process's deferred. That first composition enters the financial root, even though the resume guard is false. Configuration recreation and process recreation must both start with an unknown gate result and await the current container. A blocked attempt must retain the existing retry UI. UI saved state must never be maintenance authority.

## Export picker and lost secret

Settings data-backup fields live in ViewModel state. The export action calls `prepareExport`, copies the phrase to an in-memory CharArray, clears the field, then launches CreateDocument. The result callback calls `exportBackup` or abandons the pending phrase on cancellation. Configuration recreation retains the ViewModel, but process death creates a new one with no pending phrase. The old result can still be delivered to the recreated launcher after startup completes. `exportBackup` silently returns on a missing phrase; the destination may exist but no backup was written. Reject that result before calling the gateway and display an explicit retry message. Never put the phrase in Bundle/SavedStateHandle. Existing success, cancellation, and finally paths clear the phrase.

## Bank-qualified refund attribution

Bank adapters persist ParsedEvent account references and card-channel details. `TransactionAssembler` resolves eligible refund destinations through `FinancialContainerIdFactory`, preserving bank identity. Dashboard evidence supplies persisted records; ownership and card registry facts construct `CurrentAccountTransactionScope`. `AccountFlowClassifier` excludes credit-card refunds and resolves either a direct account destination, persisted account reference, or registered debit-card linked account. Its final `creditsScopedAccount` check discards the bank by matching the last suffix alone. `CurrentAccountSummaryCalculator` and flow detail grouping consequently attribute another bank's same-suffix refund to the selected account. Debit-card spending has a separate bank-qualified card-involvement check for refunds, which remains unchanged. Match the qualified account identity, including bank, at the cash-flow boundary. Preserve the existing unscoped fleet behavior for qualified identities and credit-card exclusions.

## Backup import, install, recovery, and restart

`inspect` reads the prefix only. Import opens the URI, detects/decrypts an envelope into a private ZIP, validates/extracts the archive, and may return early for legacy confirmation or invalid manifest, preferences, or database identity. It then checkpoints/closes the live DB, copies the extracted DB into `.incoming`, migrates and validates it, snapshots preferences, journals PREPARED, parks the original, journals OLD_PARKED, installs the new DB, journals NEW_INSTALLED, applies preferences/maintenance resets, and journals COMMITTED. Recovery retains original/preserved rollback bundles until it can safely select a database and its matching preferences.

Sensitive copies include the decrypted ZIP, extracted DB and preferences, export snapshots, incoming DB/sidecars, and recovery integrity-check copies. The ZIP and abandoned directories have an attempted wipe, but ordinary import/export finally blocks only delete directories. Successful restart exits the process before finally can execute. Additionally the existing wipe opens a truncating output stream before measuring length, so it writes zero bytes. Fix the wipe primitive itself, reuse it at discard boundaries, move incoming copy inside its cleanup try, and explicitly clean package staging and the secret before restart. Never wipe files still needed for rollback/recovery; wipe them only where existing recovery logic discards them.

## Unused raw-SMS classifier plumbing

All construction sites were searched: CurrentAccountSummaryCalculator, CurrentAccountFlowDetailGrouper, DebitCardOverviewBuilder, and AccountFlowClassifierTest. Context reads use only parsed records, bill-payment ids, currency, and SAR equivalents. No classifier branch reads `rawSmsById`. Remove the context field/buildContext input and dead forwarding. Other dashboard evidence users still need RawSms receipt timestamps (FX, loans, card history); retain those paths.

## M13 startup/capture race

Application startup completes the blocking-maintenance deferred before `runPostStartupBackgroundWork` calls `liveSmsIntake.schedulePendingProcessing`, then FX enrichment. M13 arms the CAPTURE halt and sends a PDU through IncomingSmsReceiver. Live intake stores RawSms then parks before its own scheduling. A still-running startup pending query can now see that row and independently enqueue WorkManager processing, breaking the assertion that capture alone has no ParsedEvent/transaction. Awaiting maintenance alone is insufficient. Join the actual startup job in tests before any arm/capture operation; use the existing coroutine completion barrier, no timing delay or production ordering change. Resumed processes still execute the same durable-retry scheduling.

## Invariant boundaries

No schema, parser, automation eligibility, ownership resolution, reconciliation completion policy, live work payload, retry mode, or journal stage changes. M0 parse-status authority, M4 archive limits, M5 bank identity, M13 durable capture, M17 retries, and M19 statement behavior remain covered by the complete existing CI suites. Release CI configuration stays unchanged.
