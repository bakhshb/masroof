# CI and release

Every PR to main and every main push runs the full required suite. The stable merge checks are `unit-test`, `static-analysis`, `emulator-smoke` and `emulator-lifecycle`. The smoke aggregate requires metadata/script contracts, the shared APK build, every ordinary device shard, recovery, unit tests and static analysis. Missing, cancelled, skipped or failed dependencies fail the aggregate. Release requires the latest complete successful **main push** CI for the immutable checked-out SHA, including all four checks. It never reruns the full unit suite and has no emergency bypass.

## Toolchains

Gradle runs on Temurin 27; JVM compilation and Android/Robolectric tests use the installed JDK 21 toolchain. Pure JVM modules (`finance-core`, `bank-engine`, `ml-assist`, `ledger`, `dashboard-core`) run JUnit Jupiter 6.1.3 through JUnit Platform. App and instrumentation tests keep JUnit 4 and their Android runners. Python scripts run on 3.15.2. SDK Command-line Tools 23.0 (build 16111833) are pinned explicitly. Emulator jobs align the `latest` tools path with that selected version because the emulator action prepends that path; prelaunch checks require the AVD target to match the pinned SDK package. Actions are pinned by commit with the release version beside each pin. Gradle cache ownership belongs to setup-gradle.

Dependency versions are pinned in `gradle/libs.versions.toml`; the wrapper pins
Gradle. Pure JVM dependency boundaries are enforced by
`build-logic/pure-jvm-boundaries.gradle.kts`. The existing Detekt 2 alpha is a
tooling-only exception for the Kotlin frontend; production dependencies remain
stable. Kotlin 2.4.21 with Gradle 9.8.2 and AGP 9.5.0 is beyond Kotlin's published
fully supported range (Gradle 9.7.0, AGP 9.3.1); rely on actual build and device
results, not vendor qualification. Do not enable experimental or pre-stable Kotlin
features. minSdk 26, compileSdk 37, targetSdk 36 and JVM 21 bytecode stay fixed.

## Execution and coverage

Unit tests (including pure modules), lint/detekt and the shared APK build run independently.

Financial policy tests live in `finance-core` and run through `:finance-core:test`
in the existing unit job, as do the derived-ledger tests (`:ledger:test`). The `:data` module (Room, DAOs, repositories, preference stores; Android library, Robolectric) runs through the root `testDebugUnitTest` and uploads `data/build/reports/tests/`. The existing root `detekt` task also checks finance-core
with the shared Detekt 2 configuration and ktlint wrapper. Architecture test inputs
include the relocated sources and the financial schema exported by `:data` (`data/schemas`), so a source-only boundary
change cannot reuse an unrelated cached result. `FinancialSchemaCompatibilityTest`
pins the byte-frozen v18 schema, that v22 keeps every v18 table and adds only `user_decision` and `pattern_configuration`,
the single migration from v18 and the two known identity hashes; `MasroofMigrationOpenPathTest` (and its device twin) upgrades the committed v18
fixture to production v22 (with its decisions backfilled) and through a test-only next version, upgrades a v18 file holding every legacy
decision trace (`UserDecisionBackfillContract`: every kind backfilled), checks the upgraded schema equals a freshly created one,
and proves rejected schemas, the retired v19 to v21 included, leave the file untouched. `LedgerFixturesTest` imports the committed v18 archives under production and round-trips a current export;
`BackupOlderSchemaImportTest` also imports a v18 archive holding every legacy trace and checks its backfill, a v18 archive whose patterns and recurring answers ride in `preferences.json` and land in the database, and refuses v19 to v21 archives; `LegacyPatternFileOnOpenTest` (device twin `PatternUpgradeDeviceTest`) proves the one-time copy of the old pattern file, and the `RecurringAnswer*` tests (device twin `RecurringAnswerUpgradeDeviceTest`) the one-time copy of the recurring answers; `UserDecisionLedgerConsistencyTest` checks the decision store against the ledger on the corpus;
`UserDecisionDualWriteTest` proves each decision kind appends one `user_decision` row in the transaction of its write, and that the backfill of its legacy trace equals that row.
`BackupOlderSchemaImportTest` (and `BackupMigrationDeviceTest`) imports the committed v18
archives under that next version and proves a failed migration leaves the live database byte-identical. Financial effect fixtures pin the extraction baseline. The backup/recovery device suites remain required.

`build-test-apks` builds the app and instrumentation APK once. They use the same ephemeral debug signing key. `scripts/ci_device.py bundle` verifies package names and signing certificates, records commit SHA and APK SHA-256 hashes, and downloads the official AndroidX Orchestrator/test-services APKs. Device jobs consume that immutable artifact; they do not compile again. Java setup does not compete with setup-gradle for the Gradle cache.

`ci/device-suites.json` pins APK verification to Build Tools 37.0.0 and owns the API 37 / SDK package `37.0` Google APIs x86_64 image, four ordinary shards and the stateful phase registry. Ordinary tests default into the required suite, including new tests. Unknown stateful phases, overlap and stale registry entries fail. Runtime log-only discovery of the compiled APK must match the entire source inventory before execution. Shards use deterministic longest-processing-time assignment from `ci/device-durations.json`; update measured weights when test costs change. Arabic/English and light/dark matrix variants are separate cases with the same page/scope assertions and captures.

Both ordinary and recovery emulator jobs reserve an 8 GiB data partition: the API 37 image's default internal storage cannot fit the app and test-service installation. Failed adb commands print their captured output; ordinary diagnostics include `/data` usage, and recovery logs are retained even if the first APK install fails.

All four ordinary shards use native AndroidX Orchestrator with app data cleared between cases. Production process-death uses a separate emulator. Stateful phases run directly without Orchestrator clearing their data. The process-death pairs cover capture, parsing, reconciliation, review, manual decisions, retries, atomic ignore and learning feedback.

The default case deadline is 120 seconds. A 60-second listener captures thread stacks and shutdown stage; a missed deadline fails and preserves diagnostics. Every expected case must have exactly one successful result and a terminal instrumentation frame. `adb` exit status alone is insufficient. There is no retry-to-green policy. Per-case JUnit XML, timings, instrumentation output, logcat, job/process diagnostics and screenshots are retained even on failure. Screenshot copying occurs before Orchestrator clears the app data.

## Device scope and performance

The device image is explicitly pinned rather than selected dynamically during a PR. Runtime API 37 and SDK package `37.0` are separate CI outputs: package/cache paths use the latter, runtime checks use the former. Ubuntu 24.04 remains unchanged. `minSdk` remains 26. By agreement there is no additional API 26 job: `PreVacuumBackupSnapshotTest.concurrentExport_onPre327Sqlite_importsAndReopensRoom` needs API 26–28 and is explicitly excluded from this device scope. API 37 does **not** establish that older SQLite compatibility contract.

The target for normal review is 10–15 minutes, not a proven percentile. Inspect the critical path in Actions (queue, APK build, image/boot, case execution and evidence upload) and use JUnit/JSON case timings to rebalance. Assess p50/p95 after twenty natural complete runs; do not manufacture repeated reruns or remove coverage to meet the target. Cold caches, queueing and host contention can exceed it.

See [lifecycle contracts](TESTING_LIFECYCLE.md) for the safety map and regressions.

## Release build and R8

The release build shrinks and optimizes with R8 (`isMinifyEnabled = true`); `static-analysis` builds it on every PR, so a missing keep rule that R8 can see fails there. Names are not changed (`-dontobfuscate` in `app/proguard-rules.pro`): WorkManager stores worker class names from earlier builds and logs name exception classes. Libraries bring their own keep rules; add an app rule only for a real failure.

Device tests run the debug build, so a release-only fault needs this manual smoke on an emulator after a change to R8 rules, the Android Gradle plugin or a library that uses reflection:

1. `./gradlew :app:assembleRelease`, sign the APK with the debug key (`apksigner`), install it on a clean emulator and grant `RECEIVE_SMS` and `READ_SMS`.
2. Finish onboarding with one bank, then send a purchase message: `adb emu sms send AlJazira "<body with \n between lines>"`. The row must appear under Transactions and in the dashboard total.
3. Settings → data and backup: create an encrypted backup. Then `adb shell pm clear com.baraa.masroof`, open the app, restore that file from the welcome step. The row must be back.

Last run: 2026-11-13, API 36 emulator, passed.
