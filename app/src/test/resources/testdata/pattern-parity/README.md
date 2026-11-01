# Frozen AlJazira migration reference

Captured before changing the production interpreter, adapter, financial policies,
or schema, from the production sources at `daa63747` (2026-10-26).
The experiment branch adds test tools; it does not replace that interpreter.

- `existing-bank-parse-snapshot.txt`: the frozen parse outcome of every AlJazira/STC
  fixture body, checked by `ExistingBankParseSnapshotTest`.
- `aljazira-financial-v1.json`: seven existing independent golden-ledger scenarios;
  834 persisted-fact/link/review/registry/dashboard checks. Both isolated worlds
  must also satisfy the existing independent expected financial values.

Values are SHA-256 field digests, not message bodies or clear-text identifiers.
Failures name changed fields only. Only `engine:` and `rules:` trace versions
are omitted from confidence; scores, blockers, statuses and other reasons remain.
Newly allocated account/card registry UUIDs differ across fresh databases, so only
those UUIDs are normalized across worlds. Each world's exact registry IDs must
remain unchanged after repeat import. Transaction identities and evidence links
are compared exactly. It does not install one APK over another.

Run `:app:testDebugUnitTest --tests '*AlJaziraFinancialParityTest' --tests '*ExistingBankParseSnapshotTest'` on the documented
JDK 27 Gradle runtime; the Android test task launches the installed JDK 21 toolchain.
Reports go to `app/build/reports/pattern-parity/`; tests never update these files.
A changed corpus or baseline requires explicit review, not automatic regeneration
to make a changed implementation pass. No production financial data is read.

These checks establish the current reference and comparison harness. Passing them
before enabling a replacement is not proof that learned patterns have reached
production parity. Device upgrade, backup/restore and learned-pattern cutover
remain separate acceptance gates in
[`docs/ALJAZIRA_SHARED_PATTERNS.md`](../../../../../../docs/ALJAZIRA_SHARED_PATTERNS.md).

The candidate reads `src/main/assets/bank-patterns/BANK_ALJAZIRA.patterns.json`.
`BankRuleProfileCodecTest` checks that the bundled definitions round-trip through the
codec and keep the reference refresh fingerprint.

On this host, mixed Robolectric test classes can fail loading native SQLite.
The qualified run keeps native SQLite and the original assertions, and isolates
test classes using a temporary Gradle init script:

```groovy
gradle.projectsEvaluated {
    rootProject.project(':app').tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        doFirst {
            forkEvery = 1
            maxParallelForks = 1
        }
    }
}
```

Pass it with `--init-script /path/to/isolation.gradle`; the repository's CI and
release configuration are unchanged. This is test-process isolation, not an
Android device/upgrade qualification or a substitute SQLite implementation.
