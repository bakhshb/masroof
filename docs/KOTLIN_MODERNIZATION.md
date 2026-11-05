# Kotlin source modernization

## Modernization report

Base: `05b7e045f894b6a5283022e822da99083a9e4375` (latest main at start).
Source revision: `a24494d80b86733fe4fbbaaca32d1eeb92d45a69`.
Branch: `refactor/kotlin-2.4-modernization`.
Pull request: [#347](https://github.com/bakhshb/masroof/pull/347), targeting `main`; not merged.

### Outcome

Six stable guarded `when` branches flatten five nested conditional blocks in four production
files: transaction assembly, statement ownership rejection, zero-match routing
and historical persisted-failure accounting. Branch precedence, predicates,
short-circuiting, result construction and side effects are preserved. Production
Kotlin diff: **25 lines added, 37 removed (net −12)**. No new public interfaces.


The change is deliberately small because the baseline already adopted stable
hex conversion, enum entries, a partial-ingestion guard and data-flow-based
exhaustiveness. Stable does not automatically make a feature useful for this
application. There is no claim of adopting every Kotlin release feature.

### Research and review

- [Release reference](#release-reference): Context7 queries, official release/API sources,
  introduction/stabilization versions, Android toolchain and pre-stable exclusions.
- [Code audit](#code-audit): per-opportunity current/recommended implementations, benefit,
  version, risks, tests and adopted/rejected decisions.
- [Inventory](kotlin-modernization-inventory.csv): all **614 production Kotlin files / 58,374 baseline
  lines** screened across five modules, with baseline hashes and dispositions.
  Candidate bodies/flows/tests were inspected semantically; this is not a claim
  of manual line-by-line review of every source expression.
- **4 production files modified; 613 retained.** Research, audit and validation are consolidated in this document; the full
  source inventory is the companion CSV.

Adopted feature: guards on subject `when`, introduced as preview in 2.1 and stable
since 2.2; compiled successfully with Kotlin 2.4.21 and no new opt-ins.
[Official release reference](https://kotlinlang.org/docs/whatsnew22.html#stable-features-guard-conditions-non-local-break-and-continue-and-multi-dollar-interpolation).

Rejected/deferred: explicit backing fields where they would replace read-only
Flow wrappers; context/annotation/UUID/time migrations without a concrete
benefit; `isSorted` where adjacency means gap/overlap/cadence rather than order;
Base64 or PDU conversions with compatibility risks; cosmetic aliases/string/loop
rewrites. Experimental language/library/checker APIs remain excluded. See audit
for exact locations and follow-ups.

### Compatibility evidence

- Dependency catalog, wrapper, build scripts, compiler options and CI unchanged.
- All seven Room schema exports byte-identical to base. Financial schema **18**,
  identity hash **71f1193767b3aa12bcb6cc6e7ce77147** unchanged.
- No changes to parser definitions/assets, resources, Compose code, models,
  persistence, preferences, identities, worker scheduling or test expectations.
- Existing finance-core, reconciliation, routing, scanner, dashboard fixture,
  architecture/schema and application integration tests remain intact and pass.
- Gradle dependency insight resolves compiler and runtime stdlib **2.4.21**. JVM task options use
  bytecode **21**, with language/API levels left at compiler defaults. No new
  compiler arguments were introduced; plugin-supplied arguments remain unchanged.
- Project AGP 9.5.0 / Gradle 9.8.2 exceed the KGP table's fully supported maxima.
  Successful builds are project evidence, not a claim of vendor-qualified
  compatibility. No dependency change is included.
- Local Gradle runtime was JDK **21.0.13**, also the compilation/test toolchain;
  CI's configured daemon JDK 27 was not available locally. The exact baseline
  main commit passed [full CI run 41081433811](https://github.com/bakhshb/masroof/actions/runs/41081433811)
  in that configured environment; PR CI must verify the new head too.

### Verification

| Check | Baseline | After refactoring |
| --- | --- | --- |
| App unit/integration tests | 1,871 pass | 1,871 pass |
| ui-kit unit tests | 16 pass | 16 pass |
| bank-engine tests | 121 pass | 121 pass |
| finance-core tests | 95 pass | 95 pass |
| ml-assist tests | 51 pass | 51 pass |
| Total units | **2,154; zero failures/errors/skips** | **2,154; zero failures/errors/skips** |
| lintDebug / detekt | Pass | Pass |
| Debug app / instrumentation APK | Both build | Both build |
| API 37 primary device suites | Not run locally before changes | **136 unique cases pass** across all four shards; shard 2 required one full rerun |
| API 37 process-death/recovery | Not run locally before changes | **20 phases / 10 journeys pass**, exact manifest coverage |

Increment 1 passed full finance-core plus targeted statement/reconciliation tests.
Increment 2 passed **478** targeted bank/SMS/ingestion/import-outcome tests,
including the explicit uncaptured-versus-persisted failure scenarios.
All seven existing CI protocol/routing/version checks and both family and recurring
model reproducibility paths also pass. Family/recurring trainer regression checks
pass; no generated model assets changed.

Existing baseline warnings remain, including Gradle deprecations and Android/Kotlin
warnings outside the four changed bodies. No checks or expectations were weakened.

Commands:

```sh
./gradlew testDebugUnitTest :bank-engine:test :finance-core:test :ml-assist:test --stacktrace --console=plain
./gradlew lintDebug detekt :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
./gradlew :finance-core:test :app:testDebugUnitTest --tests 'com.baraa.masroof.application.statement.StatementReconciliationServiceTest' --tests 'com.baraa.masroof.application.transaction.*' --console=plain
./gradlew :app:testDebugUnitTest --tests 'com.baraa.masroof.bank.*' --tests 'com.baraa.masroof.application.sms.*' --tests 'com.baraa.masroof.application.ingestion.*' --tests 'com.baraa.masroof.application.onboarding.HistoricalImportOutcomeTest' --console=plain
./gradlew testDebugUnitTest :bank-engine:test :finance-core:test :ml-assist:test lintDebug detekt :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
python3 scripts/ci_device.py bundle
# Run each shard 0, 1, 2, 3 on the isolated API 37 emulator:
python3 scripts/ci_device.py run --shard 0
bash .github/scripts/run-lifecycle-suite.sh production test
```

No performance improvement is claimed: there was no runtime benchmark. Build
elapsed times include caching and are not comparable performance measurements.

### Remaining opportunities and limitations

The pending-update state cast deserves separate concurrency-focused review;
the existing response-body safe-call warning could be addressed as a library-contract
cleanup; validated advisory facts could potentially reduce assertions if a future design
justifies new types. Further manual review beyond lexical screening may find
additional simplifications. None is needed for this guarded-branch change.

Two device cases are excluded by the existing CI manifest: a private-backup probe
requires private user input, and the pre-3.27 SQLite test requires API 26–28.
They are not silently counted as passing or supplied with invented private data.
The first primary-suite attempt had one UI finder failure in
`MessagePatternsJourneyTest.bankPagePreviewsPendingMessagesWithoutRemovingReviews`:
`performScrollTo` could not find the merged text node while the unmerged tree
contained it. The test helper and screen are byte-identical to baseline. The same
case passed in isolation on saved baseline APKs and on modernized APKs, then
passed inside the full affected-shard rerun. This is consistent with an
intermittent selector/readiness issue; isolated passing runs do not prove its
precise cause. No test, wait, selector, assertion or production code was changed
to obtain a pass. The original failure evidence is retained locally under
`/tmp/masroof-kotlin-first-shard-2/`, with paired probe results in
`/tmp/masroof-kotlin-case-comparison.json`. The full shard rerun passed all 35 cases, so final coverage is 136 regular
cases plus 20 recovery phases, with zero skipped or missing required cases.
The first failed attempt is not erased from this report. PR CI is pending at
submission; local results do not claim a completed hosted run for the new head.

## Release reference

Research date: 2026-10-30. Target compiler/stdlib: 2.4.21. This is source
modernization, not permission to upgrade dependencies or enable experimental flags.

### Retrieval and authority

Before source review, Context7 resolved Kotlin to `/jetbrains/kotlin-web-site`,
the repository behind kotlinlang.org. Queries covered (1) 2.1/2.2 language and
stdlib stabilization, (2) 2.3/2.4 stable versus experimental additions, and
(3) Android/Compose/KSP compatibility. Follow-up queries checked exact backing
field syntax/limitations and stdlib APIs. Coroutines was resolved separately;
`/kotlin/kotlinx.coroutines/1.11.0` was queried for cancellation/Flow contracts.
No private source or financial data was sent to Context7.

Context7 returned official repository excerpts, including historical 2.3 backing
field examples still labeled experimental. The 2.4 release notes supersede that
historical status. The official feature-status summary sometimes lists availability
at the stable/major release rather than the first preview (for example, guards
show 2.2 there, while 2.1 release notes document the preview). Introduction
versions here follow the focused release notes. The official rendered release pages, API references, Android
Room guidance and toolchain compatibility table were cross-checked directly.

### Language and compiler

| Capability | Introduction → stable/default | Potential benefit and Masroof decision | Official source |
| --- | --- | --- | --- |
| Guard conditions on subject `when` | 2.1 preview → 2.2 stable | Flatten typed-result conditions; adopt only where priority/side effects remain identical. Already used for partial ingestion at baseline. | [2.1](https://kotlinlang.org/docs/whatsnew21.html#guard-conditions-in-when-with-a-subject), [2.2](https://kotlinlang.org/docs/whatsnew22.html#stable-features-guard-conditions-non-local-break-and-continue-and-multi-dollar-interpolation) |
| Non-local `break`/`continue` in inline lambdas | 2.1 preview → 2.2 stable | Can remove loop-control flags; no flag workaround requiring this was found. Labeled collection returns are usually clearer and have different scopes. | [Inline functions](https://kotlinlang.org/docs/inline-functions.html#break-and-continue) |
| Multi-dollar interpolation | 2.1 preview → 2.2 stable | Useful for raw JSON/schema text with literal dollar signs. No production escaping hotspot justifies conversion. | [Strings](https://kotlinlang.org/docs/strings.html#multi-dollar-string-interpolation) |
| Nested type aliases | 2.2 experimental → 2.3 stable | Can scope repeated complex types to an owner. Reject aliases that merely rename existing domain types or conceal contracts. | [2.2](https://kotlinlang.org/docs/whatsnew22.html#support-for-nested-type-aliases), [2.3](https://kotlinlang.org/docs/whatsnew23.html#stable-features) |
| Data-flow-based `when` exhaustiveness | 2.2.20 experimental → 2.3 stable | Compiler accounts for earlier exclusions; prefer exhaustive type branches where useful, never remove intentional future/default financial handling. | [2.2.20](https://kotlinlang.org/docs/whatsnew2220.html#data-flow-based-exhaustiveness-checks-for-when-expressions), [2.3](https://kotlinlang.org/docs/whatsnew23.html#stable-features) |
| `return` in explicitly typed expression bodies | Default in 2.3 | Allows direct early exits; avoid converting clear block bodies for style alone. | [2.3](https://kotlinlang.org/docs/whatsnew23.html#features-enabled-by-default) |
| Explicit backing fields | 2.3 experimental → 2.4 stable | Can remove mutable/read-only backing-property pairs. Requires a private subtype field, non-open `val`, no custom getter/delegate. Do not replace Flow wrappers with the mutable flow instance. | [Properties and limitations](https://kotlinlang.org/docs/properties.html#explicit-backing-fields) |
| Context parameters | 2.2 preview → 2.4 stable, with exceptions | Avoid introducing implicit dependencies across workflow boundaries; explicit constructor/parameter contracts already fit the architecture. Explicit context arguments and callable-reference support are not covered by blanket stability. | [Context parameters](https://kotlinlang.org/docs/context-parameters.html), [2.4](https://kotlinlang.org/docs/whatsnew24.html#stable-features) |
| Annotation `@all` / default target rules | 2.2 preview → 2.4 stable | No Room/entity/serialization annotation rewrite: target changes can alter processor output and storage contracts. | [Annotations](https://kotlinlang.org/docs/annotations.html#all-meta-target) |
| Optional extra compiler checks | 2.1 | Useful diagnostics, separate from source features. Preserve current build/check configuration. | [2.1](https://kotlinlang.org/docs/whatsnew21.html#extra-compiler-checks) |
| Unified warning management | 2.2 | Per-diagnostic severity controls; do not weaken existing checks to accept a refactor. | [2.2](https://kotlinlang.org/docs/whatsnew22.html#kotlin-compiler-unified-management-of-compiler-warnings) |
| Import deprecation diagnostic improvement | 2.4 | Could narrow file suppressions/star-import workarounds; none should be removed without verifying the actual deprecated call sites. | [2.4](https://kotlinlang.org/docs/whatsnew24.html#no-more-deprecation-warnings-on-the-last-segments-of-imports) |
| JVM 21 `when` via `invokedynamic` | 2.2.20 experimental → 2.4.20 stable/default | Compiler-owned optimization, already available with installed toolchain. No manual flags or claimed speedup. | [2.4.20](https://kotlinlang.org/docs/whatsnew2420.html#new-stable-features) |

### Standard library

| Capability | Introduction → stable | Potential benefit and decision | Official source |
| --- | --- | --- | --- |
| Base64 | 1.8.20 experimental → 2.2 stable; PEM added 2.2 | JVM APIs already concise; changing decoder acceptance/error behavior or backup/encrypted formats adds risk without benefit. Retain. | [2.2 Base64](https://kotlinlang.org/docs/whatsnew22.html#stable-base64-encoding-and-decoding) |
| `HexFormat` / hex conversion | 1.9 experimental → 2.2 stable | Already adopted in hashing/identities. Retain permissive debug PDU parsing: generic parsing would accept/reject different input. | [2.2 HexFormat](https://kotlinlang.org/docs/whatsnew22.html#stable-hexadecimal-parsing-and-formatting-with-the-hexformat-api) |
| `kotlin.time.Clock` / `Instant` | 2.1.20 experimental → 2.3 stable | No replacement of `java.time` zone/period policies, stored timestamps or injected clocks. | [2.3 time tracking](https://kotlinlang.org/docs/whatsnew23.html#stable-time-tracking-functionality) |
| Common UUID API | 2.0.20 experimental → 2.4 stable, except V4/V7 generation APIs | No JVM-only benefit that warrants changing identity generation, formatting or ordering. | [UUID](https://kotlinlang.org/docs/uuids.html), [2.4 UUID](https://kotlinlang.org/docs/whatsnew24.html#stable-uuid-api-in-the-common-kotlin-standard-library) |
| `isSorted`, `isSortedBy`, descending/comparator variants | 2.4 stable | Short-circuit order checks; existing adjacent-pair logic validates spacing/overlap/cadence, not just monotonic order. No equivalent replacement found. | [Collection ordering](https://kotlinlang.org/docs/collection-ordering.html#check-if-a-collection-is-sorted) |
| Unsigned integer → `BigInteger` | 2.4 stable | No relevant unsigned conversion workaround; retain distinct Money models and decimal arithmetic. | [2.4 unsigned conversion](https://kotlinlang.org/docs/whatsnew24.html#new-api-for-converting-unsigned-integers-to-biginteger-on-the-jvm) |

### Explicit exclusions

Do not enable context-sensitive resolution, unused-result checking, collection
literals, explicit context arguments, new nullable-map fallback APIs or their
opt-ins. The first two remain pre-stable in the researched release history;
2.4 labels the latter additions experimental. Kotlin 2.4.20 also labels new
collection equality/uniqueness helpers and coroutine stack recovery APIs
experimental. These are not stable opportunities simply because 2.4.21 is installed.

Sources: [current language status](https://kotlinlang.org/docs/kotlin-language-features-and-proposals.html),
[current unused-result checker](https://kotlinlang.org/docs/unused-return-value-checker.html),
[2.4 language/stdlib](https://kotlinlang.org/docs/whatsnew24.html),
[2.4.20 exclusions](https://kotlinlang.org/docs/whatsnew2420.html#new-experimental-features).
Native/Swift/Wasm/JS changes do not apply to Masroof's Android/JVM targets.

Common atomic types were introduced experimentally in 2.1.20 and still carry
`ExperimentalAtomicApi` in the 2.4 API. Keep existing JVM atomics.
[Introduction](https://kotlinlang.org/docs/whatsnew2120.html#common-atomic-types),
[current atomic API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.concurrent.atomics/).

### Android, Compose, Coroutines, Flow and Room

- Compose compiler plugin and serialization plugin reference the Kotlin version
  catalog pin. Kotlin's Compose compiler ships with the matching Kotlin release;
  no Compose compiler flags, stability annotations, remember/effect keys or UI
  composition structure need changing. [Migration guide](https://kotlinlang.org/docs/compose-compiler-migration-guide.html).
- Compose 2.1.20 fixed default parameters on open composables and restartability
  of final overrides. Masroof has no relevant open-composable workaround to
  remove. Source information is already default on Android.
  [2.1.20 Compose changes](https://kotlinlang.org/docs/whatsnew2120.html#compose-compiler).
- Coroutines/Flow are independently versioned libraries, not APIs upgraded by
  selecting Kotlin 2.4. Preserve dispatchers, cancellation, atomic `update`,
  replay/buffering and lifecycle collection. Keep the existing read-only wrappers.
  [asStateFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/as-state-flow.html),
  [cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html).
- Room 2.8.5 uses KSP 2.3.12 here. Official Room guidance uses KSP for Kotlin;
  no switch to kapt, entity annotation rewrite or schema generation change is
  needed. Verify generated-code compilation and unchanged exported schemas.
  [Room releases/setup](https://developer.android.com/jetpack/androidx/releases/room).
- Pins are Kotlin/Compose compiler/serialization 2.4.21, AGP 9.5.0, Gradle 9.8.2,
  Compose BOM 2026.09.00, Coroutines 1.11.0, Room 2.8.5, KSP 2.3.12.
  minSdk 26, compileSdk 37, targetSdk 36, JVM bytecode/toolchains 21 remain fixed.
- Kotlin's fully supported table lists 2.4.20–2.4.21 through Gradle 9.7.0 and AGP
  9.3.1; project pins exceed that published range. Android's Kotlin class-file
  table is a separate minimum-support requirement, not proof of full KGP
  compatibility. Record actual build/device results; do not upgrade/downgrade pins
  or claim vendor-qualified compatibility. [KGP table](https://kotlinlang.org/docs/gradle-configure-project.html),
  [Android D8/R8 support](https://developer.android.com/build/kotlin-support).

## Code audit

Baseline: `05b7e045f894b6a5283022e822da99083a9e4375`.

### Coverage and method

| Module | Production Kotlin files | Lines |
| --- | ---: | ---: |
| app | 507 | 51,214 |
| finance-core | 42 | 2,442 |
| bank-engine | 21 | 1,781 |
| ml-assist | 9 | 720 |
| ui-kit | 35 | 2,217 |
| Total | 614 | 58,374 |

`kotlin-modernization-inventory.csv` enumerates every tracked `src/main/**/*.kt` file, with its baseline
SHA-256, line count, construct counts and disposition. There are no other tracked
production Kotlin source sets in this baseline. Generated sources and Gradle Kotlin
DSL are outside the source-file count; build configuration was inspected separately.

This was full-scope automated source screening plus focused semantic review of
candidate bodies, surrounding control flow, contracts and tests. It is not a claim
of manual line-by-line inspection of every expression in all 58,374 lines. Screening
looked for conditional/type dispatch, mutable/read-only pairs, casts/assertions,
stdlib/encoding/identity code, adjacency checks, labeled returns, aliases,
deprecation suppressions and escaped-dollar workarounds. Compilation and existing
architecture, financial characterization and integration tests supplement screening.
A signal is a review lead, not a defect or permission for a mechanical rewrite.

Baseline signals include 552 `when` occurrences, 14 Flow-wrapper calls in 10 files,
49 non-null assertions in 22 files, 8 adopted hex calls, 8 adjacent-pair operations,
8 type aliases, and one existing guarded `when` branch. Counts include comments or
imports where the lexical pattern matches; they are not AST diagnostic counts.

All adopted opportunities use guard conditions introduced in 2.1 preview and
stable since 2.2. The installed 2.4.21 compiler accepts them with no opt-in.
See [feature/version/source reference](#release-reference).

### Adopted opportunities

Paths below are relative to the named module's `src/main/kotlin/com/baraa/masroof/`.
Each change retains its unguarded fallback, so adding a guard does not conceal
unhandled members of the sealed hierarchy.

| ID / file | Current baseline | Replacement and benefit | Risk and verification |
| --- | --- | --- | --- |
| A1 — `finance-core: domain/assembly/TransactionAssembler.kt`, `assembleSingle` | Classified branch contains an `if`; NeedsReview contains a second subjectless `when`. | Three guarded branches express deferred classification, conservative purchase and pending transfer directly; one exhaustive decision table replaces nested branching. | Financial risk: priority or short-circuit changes. Preserve Classified before NeedsReview, deferred pairing first, conservative purchase before transfer, exact predicates and early returns. Full finance-core and statement/reconciliation checks pass. |
| A2 — `app: application/statement/ImportStatementUseCase.kt`, `import` | Accepted branch nests ownership rejection and comparison in `if/else`. | Guard Accepted with failed exact-ownership validation, then compare the remaining Accepted branch; makes the no-ledger-read rejection boundary explicit. | Rejected parse must not run ownership/comparison; unowned Accepted must not read ledger. Existing statement service tests cover both and exact bank/mask identities. |
| A3 — `app: bank/BankSmsRegistry.kt`, `route` | Zero matches branches again on nonempty suspicions. | Guard the zero-match case, then retain plain NotMatched; removes nested result construction. | No adapter loop, detector, sender rule or collision ordering changes. Suspicions must not override one/multiple matches. Existing registry/adapter contract tests cover routing and ingestion. |
| A4 — `app: application/sms/HistoricalSmsScanner.kt`, `scanOnDispatcher` | Failed ingestion conditionally increments inserted, then always increments failed. | Guard the persisted-RawSms failure, retain Failed fallback; makes the durable versus uncaptured outcomes explicit at dispatch. | Failed must increment exactly once in both cases; inserted only for non-null id. Existing scanner tests explicitly cover both and batch/reference equality. |

No new public APIs, models, dependencies, compiler flags, repository reads/writes,
entities or schema changes are introduced. The six new guards remove nested result
selection and keep the original bodies, evaluation order and fallbacks.

### Evaluated and retained opportunities

| Files / implementation | Candidate replacement | Benefit / reason retained | Version and risk |
| --- | --- | --- | --- |
| `presentation/{dashboard,review,settings,onboarding,notification,statement}/*ViewModel.kt`, `application/analytics/AnalyticsWorkflow.kt`, `application/dashboard/ChartDataWorkflow.kt`, `presentation/navigation/AnalyticsNavigation.kt`, `application/maintenance/MaintenanceCompletionSignal.kt` | Explicit backing fields instead of mutable field + `asStateFlow`/`asSharedFlow` | Reject: shorter declaration exposes the mutable instance rather than the existing wrapper; retaining both objects eliminates the claimed simplification. | Stable 2.4; encapsulation, identity and concurrent state risk. |
| `finance-core: domain/assembly/TransactionAssembler.kt`, unmatched transfer switch | Remove fallback through data-flow exhaustiveness | Already adopted: earlier family filter permits an exhaustive two-case switch with no else. No new change to claim. | Stable 2.3; financial fallback semantics must remain. |
| `app: application/ingestion/ProcessStoredSmsUseCase.kt`, Partial branch | Guard event-null dispatch | Already adopted in baseline. ReviewRequired/NonFinancial also perform common outcome construction and different logging/retry work; splitting those bodies would duplicate behavior. | Stable 2.2; retry/posting/log-order risk. |
| `app: domain/ids/RegistryEntityIdFactory.kt`, `sms/hash/SmsBodyHasher.kt`, `application/update/ApkIntegrityVerifier.kt`; `finance-core: domain/ids/FinancialContainerIdFactory.kt`; `bank-engine: interpretation/RuleInterpreter.kt`, `patterns/PatternFactInterpreter.kt`; `ml-assist: analytics/AnalyticsData.kt`, `ml/family/FamilyInputCleaner.kt` | `toHexString` instead of formatting loops | Eight files already use stable stdlib conversion. Keep exact persisted identity/hash representations. | Hex stable 2.2; IDs, checksums and reproducibility risk. |
| `app: sms/receiver/DebugSmsPduExtra.kt` | Generic hex parser | Reject: existing parser deliberately accepts input differently. Prior modernization records this contract too. | Hex stable 2.2; debug-intake compatibility risk. |
| `app: data/preferences/AndroidKeystoreGitHubTokenEncryptor.kt`, `application/backup/DatabaseRestoreRecovery.kt` | Kotlin Base64 | Java implementation is already concise on minSdk 26; decoding/padding/error compatibility requires more work than the replacement saves. | Stable 2.2; encrypted preferences and restore-journal risk. |
| `app: domain/ids/{RegistryEntityIdFactory,UserCorrectionIdFactory}.kt`, `application/review/ReviewWorkflowService.kt`, `application/patterns/MessagePatternsWorkflow.kt` | `kotlin.uuid.Uuid` | No multiplatform requirement or functional benefit; retain generation and formatting. | Common UUID stable 2.4 with generation exceptions; persisted identity risk. |
| `bank-engine: patterns/ExampleTemplate.kt`; `ml-assist: analytics/{RecurringDetector,PurchaseForecaster}.kt`, `ml/family/FamilyInputCleaner.kt` | `isSorted*` | Existing adjacency operations compute gaps, continuity, overlap/separating evidence or bigrams. Ordering alone is not equivalent. | Stable 2.4; parsing/forecast-feature risk. |
| `bank-engine: interpretation/*`, `ml-assist: analytics/*`, `app: application/dashboard/*` non-null assertions | New smart casts / filtered fact types | Compiler cannot generally carry a filter predicate through arbitrary collection transformations. New fact wrappers would exceed source modernization; retain existing validated assumptions. | Mostly pre-2.0 mechanisms; no unsupported claim that 2.4 removes arbitrary `!!`. |
| `app: presentation/settings/SettingsViewModel.kt`, pending-update restoration | Capture state once and type-dispatch instead of repeated reads/cast | Potential safety cleanup, but not a post-2.0 capability; snapshotting concurrent state needs its own explicit semantics review. Remains a follow-up. | Existing smart casts; concurrent state behavior risk. |
| `app: application/dashboard/ForeignSarMarketRateProvider.kt:44`, `response.body?.string() ?: return@runCatching null` | Read non-null response body directly | Baseline compiler reports an unnecessary safe call under the installed OkHttp contract. Deferred as a separate library-contract cleanup, rather than claiming a new Kotlin language feature. | Current 2.4.21 diagnostic; verify HTTP failure/empty-body behavior and test doubles before removing fallback. |
| `app: application/update/AppUpdateService.kt`, GitHub error mapping | Guarded `when` / delete unused private parameter | A single conjunction is already clear; changing its syntax supplies no meaningful version-specific benefit. | Guard stable 2.2; low risk, rejected as churn. |
| `app: application/patterns/PatternApprovalWorkflow.kt`, fallback review findings | Type guard instead of safe cast | The cast is intentional optional probing after higher-priority diagnostics; a new nested typed switch adds branches. Retain diagnostic priority. | Guard stable 2.2; financial approval risk. |
| Existing compatibility aliases under presentation/common, dashboard and application/dashboard | Nested aliases | These names bridge established public/architectural contracts; scoping them into a class changes callers without useful simplification. | Stable 2.3; API/architecture risk. |
| Injected clocks and financial timing/period policies across app/finance-core | `kotlin.time.Clock`/`Instant` | `java.time` remains native to Android/JVM policies and persisted zone handling. Type migration has no source-only benefit. | Stable 2.3; timezone/backup/timing risk. |
| `ui-kit: ui/theme/MasroofTheme.kt` deprecation suppressions | Delete obsolete import workaround | Suppressions apply to Android window-color calls, not deprecated-import diagnostics. Kotlin 2.4 does not obsolete the Android compatibility branch. | 2.4 import change; UI/platform risk. |
| All modules' workflow dependencies / Room declarations | Context parameters / annotation target rewrites | Stable does not imply useful: explicit dependencies and processor targets already express required boundaries. | Stable 2.4 with context exceptions; API/schema/generated-code risk. |

### Scope decisions and remaining work

No experimental collection/map/result-checker/context-resolution features are
adopted. No collection pipelines are rewritten merely to reduce line count; numeric
folds retain checked arithmetic, ordering and rounding. No runtime optimizations are
claimed without measurements. Additional manual review may find further opportunities;
the lexical inventory is not proof that none remain. The pending-update cast and
potential internal validated-fact types are explicitly deferred, not silently fixed.
