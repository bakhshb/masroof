# Local learning tools

Gradle runs on JDK 27 with the JDK 21 toolchain. The pure `ml-assist` test suite
uses JUnit Jupiter. Run focused checks locally; full suites run in PR/main CI.

The Android runtime and offline recurrence trainer share the same Kotlin
features, fitter and inference. There is no Python/TensorFlow dependency in the
application.

## Reproducible shipped artifacts

```sh
./gradlew :ml-assist:trainRecurringSeed -PrecurringCheck=true
```

The recurring corpus is currently empty. Its shipped artifact/report explicitly
say `accepted=false`; this is not a qualified pretrained model. Do not replace
real qualification data with synthetic examples or fabricated independent IDs.

Message families have no trained model: the Naive Bayes seed never qualified a
family and was removed with its Python trainer. Message learning is exact
human-template memory built on-device from explicit decisions.

## Recurrence corpus

TSV fields: `seriesKey`, `contributorGroup`, `partition`, `humanLabel`,
`ruleLabel`, then eight numerical features from `RecurringDetector.series`.
Partitions are `train`, `validation`, `test`; labels are `NONE`, `WEEKLY`,
`MONTHLY`. Contributors and series cannot cross partitions. The features carry
no merchant name, account identifier, date or absolute monetary amount. Do not
reimplement feature extraction in Python.

The opt-in instrumentation `PrivateBackupAnalysisTest` accepts an encrypted
backup and a transient password file in the target app's private files directory
(`private-backup.masroof`, `private-backup-passphrase`) and argument
`privateBackup=true`. This private probe is excluded from public CI because its inputs must stay private; normal feature and lifecycle tests remain required. Use an isolated emulator. It runs production analysis in a
separate storage context, compares ledger fingerprints, deletes the private
copies and emits aggregate diagnostics plus `private-recurring-corpus.tsv`.
The export includes only explicit recurring feedback or saved human commitments;
all rows initially belong to training under one pseudonymous contributor group.
Other backups from that same person must retain that contributor group and
partition. This export cannot independently qualify a global model.

```sh
./gradlew :ml-assist:trainRecurringSeed \
  -PrecurringCorpus=/private/learning/recurring.tsv \
  -PrecurringOutput=/private/learning/recurring-model.json \
  -PrecurringReport=/private/learning/recurring-report.json
```

Validation alone chooses the threshold. The untouched final test can veto
qualification, never choose weights or thresholds. Qualification requires 95%
precision, at least 10 percentage points more recall than rules, no increased
false-positive rate, >=30 proposed independent series per cadence and >=30 hard
negatives in both validation and test. Personal on-device training has its own
stable held-out series gate; it cannot activate an ineligible base model.

## Forecast replay

`ForecastReplay.evaluate` and the developer-only workflow replay use a strict
historical prefix with no final origin-day amount. Future days supply evaluation
targets only; gaps prevent accuracy claims. They never write retrospective
published forecasts. The existing live forecast comparison policy is unchanged.
