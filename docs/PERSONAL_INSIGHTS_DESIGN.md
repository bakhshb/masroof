# Personal spending and forecasting — design proposal

Status: implemented and locally verified on `feat/personal-spending-insights`;
full follow-up PR CI is required before merge. Statistical seeds remain ineligible.
Baseline: PR #329, `feat/continuous-local-learning`, `d2d3ebe5`.
CI #943 / run 40097906593 succeeded on that head. PR is draft and unmerged
at inspection on 2026-10-24. The follow-up targets `main` and depends on #329
landing first; its implementation does not enlarge or merge #329.

## Objective and assumptions

Make existing Spending, Forecasts and Commitments explain relevant personal
patterns, accept useful explicit feedback and demonstrate measured improvement.
Keep the existing navigation, purchase scope, salary periods and design system.
Message learning remains in Settings → understanding messages from corrections.
This is an on-device advisory feature, with private developer evaluation outside
the app. It does not infer ownership, change balances, or post financial entries.

Success means a user can understand why a result appeared, inspect its evidence,
correct an interpretation, and see the actual method in use. A model version or
a growing training count is not evidence that accuracy improved.

## Accepted capability map

These are product work areas, not new Gradle modules or service layers.

| Id | Responsibility | Dependencies |
|---|---|---|
| evidence-audit | Review feature exclusions, coverage and evidence eligibility | Existing financial/learning workflows |
| spending-context | Explain unusual spending and repeated merchants | evidence-audit, scoped approved financial facts |
| recurrence-feedback | Improve review of likely repeating payments | evidence-audit, existing recurring detector and feedback |
| message-review | Select useful message examples and explain correction effects | evidence-audit, existing bank/interpretation workflows |
| forecast-explanation | Explain forecast method, readiness and measured error | Existing forecaster, coverage and performance policies |

Build order: evidence-audit → forecast-explanation → spending-context →
recurrence-feedback → message-review. Base-model qualification is a separate
data milestone; it is not a prerequisite for factual spending explanations.
Merchant-name linking is deferred until an audit establishes that duplicates
materially harm these results.

## Existing implementation: retain and extend

| Function | Current implementation | Proposal |
|---|---|---|
| Spending | Actual/net purchase scope, equal-length comparison, components, merchants and contribution drilldowns | Retain; add at most three eligible contextual observations |
| Forecasts | Actual + estimated remainder, daily bars, quality, performance/history | Retain; make method/readiness and error understandable |
| Recurrence | Numerical classifier plus deterministic rules, durable decisions and undo in #329 | Retain; evidence-first review and explicit feedback semantics |
| Messages | Rules first, exact human-template memory (statistical model removed) | Retain; review unsupported formulations and select diverse examples |

Current sources: `AnalyticsScreen.kt`, `AnalysisSpendingCharts.kt`,
`AnalysisForecastViews.kt`, `AnalysisExplanationViews.kt`,
`FinancialAnalysisReader.kt`, `AnalyticsWorkflow.kt`,
`PurchaseForecaster.kt`, `RecurringLearning.kt`, `FamilyClassifier.kt`.
Readiness must come from current production workflow output, not the backup's
calendar span alone. Archived snapshots are not independent accuracy samples.

## Models and authority

| Use | Method | Training | What the user sees |
|---|---|---|---|
| Initial forecast | Mean by weekday, rare huge days capped at the 98th percentile | Local covered spending history | “Initial estimate based on your history” |
| Personal forecast | Huber regression with L2 (existing) | Local, eligible history only | “Personal estimate”; error in SAR over a named horizon |
| Recurrence | Regularized three-class softmax / logistic classifier (existing in #329): NONE, WEEKLY, MONTHLY | Reviewed base corpus offline; explicit personal labels on-device | “Rules”, “Your confirmation”, or “Learned model” |
| Message family | Exact human-template memory (Naive Bayes removed: it never qualified a family) | Explicit human decisions on-device | Source of interpretation and whether a correction was used |
| Unusual spending | Median and median absolute deviation; no new trained model initially | Recomputed from comparable prior approved facts | Observed amount vs personal reference, with evidence |
| Review selection | Grouping and priority rules initially | No learned ranking model | A small set of distinct examples worth reviewing |

No LLM, embeddings, cloud inference or additional ML dependency is required
for the first release. Predictions, ordinary dismissals and absence of correction
must never become training truth. Statistical recurrence cannot activate an ineligible
base model. Do not relax gates to make the preview or private evaluation succeed.

## User journeys and screen design

### Spending

Keep period selector and actual total first. Keep “How did your spending change?”
display-only; current chart/merchant taps continue opening scoped transactions.
Append a compact “Higher payments than usual” section after the existing merchant
and change charts. Keep those charts and the historical-period selector intact.
Maximum three observations, only when the evidence supports them; no empty
“AI” card. Each merchant has paired shared bars for the current payment and prior
median, with amounts, payment date and reference count. Tapping a merchant's
comparison opens the existing payment evidence page; “View payments” remains an
accessibility action rather than a visible text button. The help icon stays
separate from navigation. Both bars use the same scale within that pair. The
existing evidence screen retains the exact comparison window, prior transactions
and contextual explanation.
Long explanations of spending scope, comparison dates, merchant net amounts and
statistical observations use the existing exclamation-mark guidance buttons.
The chart keeps its title, amounts, dates and concise sample counts visible.

First candidate: an unusually large payment at the same merchant within the same
bank-qualified source scope, currency/approved SAR conversion and eligible type.
Provisional rule: at least six eligible previous payments on distinct dates,
covering at least 28 days; current amount exceeds the prior median by all of
3 × MAD, 50%, and SAR 20. With MAD = 0 the percentage and absolute floors still
apply. These are conservative product heuristics to test, not accuracy claims.
Compute the reference before the candidate date; ambiguous same-day duplicates,
refunds, missing FX and unresolved evidence cannot teach the reference. Preserve
correct large payments in actual totals. Do not describe a deviation as fraud.

Second candidate, already grounded in existing spending comparisons: an increase
at a frequent merchant over the same number of completed salary-period days.
Reuse existing comparison completeness, net amounts and evidence IDs. Explain
“contributes to the difference”, never infer a personal cause. Merchant and
component contributions overlap and must not be summed together.

No new notification category initially. Put observations inside Spending and
measure usefulness before introducing interruption or learned alert ranking.

### Forecasts

Keep current actual/remainder split and daily chart. In the main summary add one
short method line: “Initial estimate” or “Personal model in use”. Next to it,
An exclamation-mark button beside the method opens concise contextual help with
the method, current blocking reason, qualification rule and spending scope.
No explanation expands inside the chart card. The existing guidance popup opens
only on a tap and dismisses with Back or outside press. Detailed history coverage,
training dates and numerical qualification requirements remain in Data quality.
Readiness-chart explanations also use contextual guidance. The existing daily
chart, visible availability warnings, numerical labels and legends are retained.

Use the existing quality detail for permission, review and missing-coverage
actions. Do not turn transaction-free days into proven zero-spend days. Keep
data quality in Forecasts instead of duplicating it in Spending.

Show errors only when eligible evaluation samples exist: e.g. “7-day forecasts:
average error SAR … across … checks”. Empty state: “Not enough independent
checks yet”. Never show a generic accuracy percentage or convert a model score
into a probability of being correct. The historical replay is a developer
evaluation, not a forecast the user supposedly received in the past.

Retain initial forecast requirements (56 covered days, two complete salary
periods), Huber requirements (120 consecutive covered days, four complete periods,
20 purchase days), eight eligible checks per core horizon, improvement and
per-horizon regression limits. 120 days permits a candidate fit; it does not
guarantee activation. Retain the live three-period rollback/recovery policy.

Confirmed commitments already represented by spending or a projection must not
be added to the forecast again. Any later combined cash-flow feature needs its
own reconciled scope and is outside this increment.

### Commitments

Keep confirmed dues first, candidate suggestions second and previous decisions
with undo. Candidate summary: cadence, estimated amount/date, method actually
used and prior payments. “Review and save” opens the existing commitment editor.
The reviewer can change the draft; only Save applies it.

“Not recurring” records an explicit negative. “Don't add” hides the suggestion
without a negative training label. Undo revokes the decision. Removing/changing a
commitment revokes its old label; missing evidence prevents statistical training.
Do not turn every frequent merchant into a subscription. Keep bank/container
identity in series keys. Show “Needs reviewed examples” rather than pretending
the shipped recurring model is active.

### Message learning (existing Settings route)

Separate “I remember your confirmed correction” from “I can generalize to new
wording”. Audit rejected titles by reason and structural wording; do not merely
expand a whitelist with personal names or strip security/negation words.
Any cleaner revision increments its version and is evaluated using production
normalization/validation; posted history and human decisions are preserved.

Review selection groups bank-qualified templates and prioritizes unresolved
ambiguity, unsupported wording and underrepresented families. While no model
is qualified, use structural diversity, not fabricated confidence scores.
Choose at most three examples for an optional review session; no forced prompt
on app open. Answering one example must not silently label all related messages.
Show the proposed scope and require an explicit action for any future application.
Keep a source decision/evidence ID and feature version; do not store duplicate
numerical features or model-candidate weights.

### Shared states and accessibility

- Fresh, refreshing, insufficient history, missing coverage, unsupported wording,
  failed evaluation and fallback all have distinct text and actionable detail.
- A stale observation never looks current; removed evidence removes its derived
  observation. Preserve retry state for failed feedback writes.
- Use existing MasroofCard/SectionHeader/AmountText/MoneyRow and chart components;
  MaterialTheme/MasroofThemeExtras and existing spacing/shape/icon tokens only.
- All sheets use MasroofBottomSheet: fixed header/actions, scrolling body,
  Save/Apply commits, other dismissal discards, busy financial writes block exit.
- Arabic RTL, English LTR, light/dark, large text, TalkBack labels and minimum
  touch targets. Distinguish actual/estimated by text/pattern as well as color.
- Preview uses fictional monetary examples and generic merchant names; it is
  a product proposal, not an analysis result or an installed app update.

## Evidence, storage and module boundaries

Application analytics workflows capture approved scoped facts once; calculations
run after the Room read transaction. Pure numerical logic lives in `ml-assist`;
presentation renders precomputed values. Dashboard calculations stay in matching
builders/calculators; dashboard loading remains read-only and never reparses SMS.
Message text and bank-specific interpretation stay in the interpretation/bank
path. An audit may enumerate explicit fact queries, never add dashboard listAll
or per-row raw-SMS reads.

Derived observations and review queues are rebuilt in memory. Reuse existing
feedback and decision stores. Introduce no speculative persistence for candidates,
features or weights. If a later confirmed merchant-alias preference requires
storage, propose its minimal advisory schema/archive migration and lifecycle
tests separately before implementation. Financial schema remains 18.

## Evaluation and acceptance

1. Factual parity: balances, spending totals, periods, IDs, financial schema and
   human decisions unchanged; observation evidence matches its drilldown exactly.
2. Spending context: time-prefix references only, stable scoped identity, no
   fabricated anomalies in constant spending, explicit abstention for sparse or
   invalid evidence; corrections/deletion refresh observations.
3. Recurrence: unchanged qualification gates, labels and undo semantics; weekly,
   monthly and hard-negative cases in independent validation and final test.
4. Message learning: audit excluded formulations, role/security/negation/refund
   regression tests, held-out templates and untouched final tests; zero added
   wrong accepted families. Seed qualification remains separate from personal fit.
5. Forecasts: same production forecast outputs and eligibility; named-horizon
   error metrics, no future-data leakage, no invented historical publications,
   no activation just because calendar history reached 120 days.
6. Product validation: private evaluation first, then optional manual review of
   a dated set of observations. Record useful / not useful / factually wrong
   separately from dismissal; do not promise a measured benefit before this.
7. Device coverage: migration/restore/upgrade/process-death as applicable,
   draft discard, busy save, reopen, Arabic/English, light/dark and large text.

User data stays outside git and public CI; repository tests use minimized,
reviewed fixtures or synthetic behavioral examples without accuracy claims.

## Implementation environment and focused verification

Kotlin/Compose/Room, existing JVM ML implementations, no new dependencies.
Use existing code style, immutable result models and application workflows, e.g.
`val observations = builder.build(approvedFacts, period, today)`; a Composable
receives the resulting observations and performs no money aggregation or parsing.

```sh
export JAVA_HOME=/tmp/masroof-jdk17
export JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/home/debian/.cache/masroof-learning-tmp
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 :ml-assist:test --console=plain
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 :app:testDebugUnitTest --tests '*application.analytics.*' --tests '*lab.beta.analytics.*' --tests '*application.dashboard.*' --tests '*FamilyLearningTest' --tests '*PackageDependencyRulesTest' --console=plain
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 :app:lintDebug :app:detekt :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Use targeted device classes from `ci/device-suites.json` on an isolated emulator;
do not compile heavily alongside the emulator on this host. Full tests belong
in PR/main CI; release CI stays unchanged. Always inspect failures before editing;
never weaken validation or tests to pass.

## Delivery sequence

1. Audit rejected message features and current model participation. Produce an
   aggregate private report and establish exactly what is supported today.
2. Ship truthful forecast explanation using existing results; no model changes.
3. Add one spending observation end to end, with evidence and sparse-data states.
4. Add the second comparison observation only if it adds information beyond the
   existing contribution chart; otherwise improve the existing explanation.
5. Polish recurrence review/undo and measure usefulness of current rule output.
6. Improve supported message formulations, then optional diverse review selection.
7. Qualify base models with enough independent reviewed data; ship eligible
   artifacts only after evaluation. Continue local personalization under #329 gates.

Open validation decisions: usefulness of the proposed anomaly heuristic, whether
merchant aliases are actually needed, and whether any new stored preference is
worth its migration/lifecycle cost. None blocks reviewing this proposed design.

## Review evidence for this proposal

- Inspected production screen/workflow/model sources at the baseline above.
- Re-ran `AnalyticsJourneyTest#forecastsKeepLedgerParityAcrossCorrectionsAndReopenAndUiDraftsOnlySaveExplicitly`
  on the dedicated API 36 emulator with the existing APKs: passed, one test.
  Reviewed the current Arabic Spending and Forecast daily-chart screenshots.
- Interactive proposed screen fragment lives in ignored `app/build/design-preview/`;
  it is not bundled in the app. Monetary examples and merchant names are fictional.
- Fragment IDs/ARIA references and JavaScript syntax checked. A small Node/DOM
  simulation passed tabs, readiness states, evidence, negative/dismiss/undo,
  save cancellation and optional review entry. No connected browser
  was available to visually inspect the HTML prototype; this does not establish
  browser rendering/accessibility or production UI acceptance.
- The preceding bullets record design review before implementation. Implementation
  remaining qualification work is tracked in [WORK_REMAINING.md](WORK_REMAINING.md);
  completed validation is recorded in the relevant pull request.

## Delivered increment

Existing screens now explain the active forecast and qualified errors, show up
to three unusual-payment observations with exact prior evidence, clarify recurring
feedback and offer a small optional review using existing resolution authority.
Reviewed return wording is supported with a cleaner-version bump and reproducible
seed regeneration. No duplicate contribution feature, merchant aliases, new ML
dependency, model-weight cache or additional financial/advisory schema was needed.

Nine fixture-based device cases and the additional local private probe passed;
the latter verified observation participation while preserving the ledger. User-rated
usefulness remains unmeasured. Independent base qualification remains a separate
data milestone, with the original safety gates intact.
