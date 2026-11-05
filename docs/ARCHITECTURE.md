# Production architecture

Masroof has one Android application, one financial database and one bank interpretation engine. The tested engine and advisory features are integrated into the existing app; the parallel Lab application and generated source replica are removed.

## Modules

- `app`: presentation → application workflows → domain/parsing/data/SMS/bank ports and the pure financial core. `AppContainer` wires the only production runtime.
- `bank-engine`: pure Kotlin message-pattern matching, literal-role interpretation and extraction evidence, depending only on `finance-core` value contracts. `MessagePatternSmsAdapter` maps its output through `InterpretationDraftMapper` and the production `ParseFinalizer`.
- `finance-core`: production money/value models, salary and statement periods, bank transaction time, deterministic container/transaction identities, financial classification/impact, transfer matching and assembly, and statement matching. Policies consume supplied facts and return decisions or transactions in memory; there is no ledger, repository, Android dependency or I/O. Existing engine value contracts remain available separately.
- `ml-assist`: exact human-template family memory, cleaned templates, forecasting and recurring-pattern detection. The engine consumes an injected assistance contract rather than depending on ML implementation.
- `ui-kit`: shared theme, typography, fonts, cards and charts. It cannot access financial storage.

Source namespaces use `com.baraa.masroof.engine.bank`, `com.baraa.masroof.finance.value`,
`com.baraa.masroof.ml` and `com.baraa.masroof.analytics`. Application integration
tests use `com.baraa.masroof.integration`. Historical `lab`/`beta` source paths and
source file prefixes have been removed. Existing serialized backup keys and retired
work identifiers keep their original spelling for upgrade compatibility.

## Message-pattern ownership

Keep the existing five modules. Organize by responsibility; a filename containing
`Pattern` does not mean its approval, storage or UI belongs in the engine.

| Location (under `com.baraa.masroof`) | Owns |
| --- | --- |
| `bank-engine: engine.bank.patterns` | Immutable definitions, compilation, matching, extracted facts and provenance |
| `bank-engine: engine.bank.interpretation` | Shared role, text, money, date and safety primitives |
| `app: bank` | Bank routing/detection and mapping engine output into validated parser drafts |
| `app: application.patterns` | Teaching, revisions, financial approval, pending preview/application and display models |
| `app: application.interpretation` | Configured adapter snapshots, family assistance and automatic-write eligibility |
| `app: application.ingestion` | Durable processing, protected evidence and retry/resume orchestration |
| `app: data.patterns` | Strict JSON codecs, immutable revision persistence and atomic file replacement |
| `app: application.backup` | Pattern capture, restore and rollback integration |
| `app: presentation.patterns` | Render application models and collect user input |
| `finance-core` | Financial classification, matching and assembly from supplied facts |

`AppContainer` loads `app/src/main/assets/bank-patterns/*.patterns.json`, installs
missing bundled definitions before admitting work, and wires the store into
`ConfiguredPatternAdapters`. Each routing attempt receives a complete immutable
snapshot. `BankSmsRegistry` evaluates every adapter; only a resolved bank reaches
`PatternFactInterpreter`. AlJazira uses the generic role interpreter within that
snapshot; STC's six bundled templates require explicit financial approval.

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
setups remain compatible; the financial schema stays v18.

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
Editing a built-in teaches a new pattern and turns the built-in off once the replacement is approved. Financial schema v18 and the existing
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
RawSms, parsed evidence, corrections or posted financial rows. Financial Room schema
v18 is unchanged; deleted patterns cannot be recovered through revision restore.

Keep integration fixtures on the bundled production definitions. Kotlin rule
profiles live only in `bank-engine/src/testFixtures/`, shared through Gradle's built-in test-fixtures support. The offline CLI is an explicit test tool. Neither
is another production definition source. Preserve the frozen AlJazira baselines.
[Pattern facts](PATTERN_FACT_CONTRACT.md) describes the current write contract;
[AlJazira compatibility](ALJAZIRA_SHARED_PATTERNS.md) records the frozen-reference
checks and remaining signed-upgrade/restore release gates. The production ownership
table above supersedes the completed migration plans and prototype execution logs.

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
transaction. Dashboard builders/calculators and evidence scoping also stay in
`app`. The statement match policy and direction value live in core; canonical
CSV parsing, its stream interface and reconciliation workflow stay in app.

`FinancialTransaction.fromSingleEvent` shares the construction of a transaction
from one parsed event between automatic assembly and manual review. Callers
still authorize the operation and choose its type, endpoints and timing; the
factory does not classify, resolve ownership or write to storage. Manual and
automatic endpoint precedence remain separate.

This extraction preserves schema v18 and the existing write paths. It introduces
no backfill, history reparse, new database, worker, backup format or UI behavior.
Policy tests run directly on JVM in `finance-core`; app integration tests cover
the unchanged persistence, orchestration and dashboard paths. Architecture checks
scan both source roots, require the moved policies to exist in core, reject
app-side copies and reject fully qualified calls that bypass module import rules.

## Processing and authority

Live SMS is captured durably before WorkManager receives a raw SMS id. Historical intake parses a batch before one discovery/reconciliation/review pass. Validation, confirmed ownership and manual decisions retain financial authority. Informational/OTP messages never post. Reconciliation failures retain durable retries.

The engine uses rules first. Eligible local family inference never supplies an amount, ownership or approval. Explicit corrections feed local learning. Rule/model refreshes target unresolved, unposted evidence without a manual decision. Previously posted history is preserved.

Message learning is "remember my decision", per bank, for every configured bank. Each bank's exact-template memory is built only from that bank's human decisions; there is no statistical message model. A remembered "not financial" decision settles a later message with the same template that the rules could not identify, in any bank, and `InformationalMessagePolicy` keeps it out of review. A remembered financial family is used only where the bank's role rules ask for one; it never supplies money, ownership or posting. Conflicting decisions for one template propose nothing. Banks with role rules re-read their pending messages when the rules or their decisions change; banks without role rules refresh only the pending messages a learning change would change, so taught patterns are still applied explicitly.

## Data and recovery

`masroof.db` supports schema 18 only, with its fourteen tables and unchanged identity hash. Older database and backup schemas are rejected without migration or destructive fallback. Automatic schema-fact backfill is removed; already queued backfill work completes without parsing or writing. Existing preference files, Keystore alias, app id and worker names remain stable. `learning.db` stores labels/provenance; `analytics.db` stores advisory results and preferences. Old Lab files are neither imported nor deleted automatically.

The learning store is schema 2 and the analytics store is schema 4. Posted type edits atomically update the financial transaction and its latest `USER_FINANCIAL_TYPE` review decision in the unchanged financial schema. Message learning rebuilds from durable decisions before enabling personal assistance; no derived message-model cache is written or loaded. Exact template suppressions include bank and feature identity and revoke human-template assistance. Financial `EXPENSE`/`INCOME`, ownership decisions and field-only edits do not invent family labels. Unsupported or ambiguous decisions have an explicit feedback disposition.

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

Encrypted backups retain the existing envelope and accept financial schema 18 only. Optional analysis is included in the durable restore journal for commit/rollback. Restoring an older backup clears stale analysis and rebuilds available results. Learning state is invalidated and reconstructed from retained human decisions.

See [AGENTS.md](../AGENTS.md) for enforced boundaries and [ANALYTICS.md](ANALYTICS.md) for model and alert policy.

### Closing SQLite-backed services

Application shutdown stops producers and drains admitted startup/database work before closing Room. Never cancel a first-open Room continuation and assume SQLite has stopped: its connection callback can still own the open-helper monitor while invalidation setup requests the Room close read lock. Taking Room's close write lock at that point deadlocks. Startup, learning/audit and analytics drain normally; the database access gate also drains independent WorkManager leases. `StartupQueryShutdownTest` controls that native first-open boundary.

## Continuous local personalization

Message learning is exact human-template memory only; there is no held-out statistical message candidate.

Recurring detection has a small numerical L2-regularized softmax classifier,
shared by offline training and on-device fitting. Entire series are held out;
only explicit human recurrence decisions and currently saved human commitments
supply labels. Prediction, rejection-by-absence and ordinary dismissal do not.
Series identities include the existing bank-qualified source container. Rules
remain the fallback when neither seed nor personal model is eligible; an eligible
model's abstention never falls through to rules. Amount/date estimates remain
deterministic. The current reviewed recurring seed corpus is empty, and its
artifact explicitly reports ineligibility, rather than shipping invented training
labels or forcing activation.

Analytics schema 4 adds durable recurring feedback, including an intent recorded
before the financial save. A later pass completes that intent only when the
saved commitment's source/cadence agrees. Feedback failure cannot undo a saved
financial commitment. Legacy rejection remains dismissal, not a negative label.
Explicit negative preferences remain until undone; missing evidence disqualifies
them from statistical training. Removing/changing a confirmed commitment revokes
its earlier training label. Numerical models are rebuilt from current facts and human decisions; unused
candidate weights and duplicate feature snapshots are not persisted.
Archive 4 retains compatibility with archives 1-3 and financial schema 18.

Forecast replay uses the existing forecaster on a strict historical prefix with
an empty origin day; later actuals are evaluation targets only. Replay results
never become published forecast history. Existing readiness and live performance
rollback gates are unchanged.

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
