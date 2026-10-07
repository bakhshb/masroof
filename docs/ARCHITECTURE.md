# Masroof v2 — Architecture Specification

## 1. Architecture Goal

Masroof v2 must be a clean, modular Android application where:

- SMS ingestion is isolated from parsing.
- Parsing is isolated from financial business logic.
- Bank-specific logic is isolated from generic domain logic.
- UI contains no business rules.
- Every important rule can be tested without Android UI.
- Adding another bank later does not require rewriting the core domain.

---

## 2. Clean Rewrite Guardrail

The new source tree must be created intentionally.

Legacy production code must not be copied into the new architecture by default.

Before implementing a reused component, explicitly verify that it matches this specification.

No compatibility layer is required for old architecture.

---

## 3. Proposed Layers

```text
presentation
application
domain
data
sms
parsing
bank
```

Conceptual direction:

```text
Android / SMS
     ↓
Data ingestion
     ↓
Parsing
     ↓
Application use cases
     ↓
Domain
     ↓
Persistence
     ↓
Presentation
```

Dependencies should point toward the domain, not toward Android UI.

---

## 4. High-Level Flow

```text
Android SMS Provider / BroadcastReceiver
              │
              ▼
        SmsDataSource
              │
              ▼
          RawSmsStore
              │
              ▼
       MessageNormalizer
              │
              ▼
          BankDetector
              │
              ▼
      BankMessageClassifier
              │
              ▼
        FieldExtractors
              │
              ▼
           Validator
              │
              ▼
         ParsedEvent
              │
              ▼
      OwnershipResolver
              │
              ▼
      TransactionMatcher
              │
              ▼
     TransactionAssembler
              │
              ▼
    FinancialTransaction
              │
              ▼
      TransactionRepository
              │
              ▼
               UI
```

---

## 5. Suggested Modules / Packages

A single Android app module is acceptable initially, but package boundaries should be strict.

Suggested structure:

```text
com.masroof

core/
  money/
  time/
  result/

domain/
  model/
  rules/
  service/
  repository/

application/
  usecase/

sms/
  model/
  datasource/
  receiver/
  scanner/

parsing/
  normalizer/
  classifier/
  extractor/
  validator/
  model/

bank/
  aljazira/
    sender/
    classifier/
    extractor/
    rules/
    fixtures/

data/
  room/
    entity/
    dao/
    mapper/
  repository/

review/
  domain/
  data/

presentation/
  onboarding/
  dashboard/
  transactions/
  review/
  accounts/
  settings/
```

Do not create large `utils`, `helpers`, or `manager` dumping-ground packages.

---

## 6. SMS Ingestion

Two paths:

### Historical scan

```text
Android SMS Provider
   ↓
HistoricalSmsScanner (oldest → newest)
   ↓
HistoricalSmsBatchProcessor.Batch.ingest
   (CaptureBankSmsUseCase → ProcessStoredSmsUseCase.parseAndStore, per row)
   ↓
Batch.finish (once per scan)
   ownership discovery for stored events → reconcileBatchDetailed → review refresh
```

Historical import never reconciles per SMS. `finish` returns success or an incomplete
stage. A correctness-blocking failure keeps the stored evidence and writes the affected
financial rows to `processing_retry` with `mode = HISTORICAL_BATCH` in one transaction,
then enqueues one batch recovery worker. That mode is stored on the retry row. It is not
inferred from whether a review row exists, so a review update that writes some reviews
and then fails still leaves the whole set on the batch worker. That worker reloads every
`HISTORICAL_BATCH` row, including rows that already have reviews, and reruns ownership,
reconciliation, and review refresh once. It does not reparse the SMS and it does not
enqueue a live worker per row. Success clears that historical set in one transaction.
If the marker transaction fails, no partial set is kept and `finish` can be retried.
A scan that fails mid-way (permission or provider error) keeps its counters and
evidence and still runs `finish` for events it stored.

### New messages

```text
IncomingSmsReceiver (assemble multipart, provider timestamp or device clock)
   ↓
LiveSmsIntake → CaptureBankSmsUseCase (durable RawSms)
   ↓
LiveSmsWorkScheduler (unique work "live-sms:<rawSmsId>", input = rawSmsId only)
   ↓
LiveSmsProcessingWorker → ProcessStoredSmsUseCase.process(rawSmsId)
```

The receiver's `goAsync` window covers only capture + scheduling; parse and
reconciliation never run within the broadcast lifetime.

Both flows must converge into the same processing pipeline.

Capture and processing are separate application boundaries:

| Use case | Responsibility | Never does |
|---|---|---|
| `CaptureBankSmsUseCase` | route bank → dedupe → persist `RawSms`; returns `BankSmsCaptureResult` (rawSmsId + route) | parse, ownership, reconciliation, dashboard |
| `ProcessStoredSmsUseCase` | load stored `RawSms` (by row or id) → parse → persist `ParsedEvent` → ownership discovery → reconciliation → review | route-time dedupe, provider I/O |
| `ProcessRawSmsUseCase` | compatibility facade: capture, then process in the same call | — |

`RawSms` is durable before any long derived processing begins; both use cases are idempotent.

---

## 7. Raw SMS Deduplication

Deduplication should be handled before parsing.

Candidate identity inputs:

- Android message ID if reliable
- sender
- timestamp
- normalized body hash

Suggested unique strategy:

```text
deviceMessageId
OR
hash(sender + timestamp + body)
```

Reprocessing the same SMS must not create another raw record.

---

## 8. Processing Pipeline

Suggested use case:

```kotlin
ProcessRawSmsUseCase
```

Conceptually:

```text
load RawSms
  ↓
normalize body
  ↓
detect bank
  ↓
classify message
  ↓
extract fields
  ↓
validate
  ↓
persist ParsedEvent
  ↓
resolve known ownership
  ↓
attempt transaction matching
  ↓
create/update FinancialTransaction
  ↓
mark review if needed
```

---

## 9. Normalizer

`MessageNormalizer` must handle formatting noise without destroying useful meaning.

Possible normalization:

- Unicode normalization
- Arabic/Latin digit normalization where useful
- whitespace normalization
- punctuation normalization
- colon variants
- repeated spaces
- line ending normalization
- English lowercase shadow representation

Keep:

- original body
- normalized body

Do not overwrite raw data.

Three representations, each with one job:

| Field | Content | Use |
|---|---|---|
| `originalBody` | bytes as received | evidence / traceability |
| `normalizedBody` | NFC, Latin digits, trimmed lines, collapsed spaces; letters untouched | display-safe extraction (merchant, counterparty, biller, reference) |
| `comparisonBody` | `ArabicTextFolding.foldForComparison(normalizedBody)`: lowercase, أ/إ/آ/ٱ→ا, ى→ي, no tatweel / diacritics / bidi marks, colon and Arabic separator variants unified | matching only — never displayed |

Patterns and keywords matched against `comparisonBody` must be folded the same way:
build regexes with `comparisonRegex(...)` and keyword checks with
`containsComparison(...)` (`parsing/normalizer/ComparisonMatching.kt`). Turn a
comparison match range into display text with `NormalizedSms.normalizedSlice(range)`,
which maps offsets across dropped characters.

---

## 10. Bank Detection

Suggested interface:

```kotlin
interface BankDetector {
    fun detect(sender: String, body: String): BankDetectionResult
}
```

Initial implementation:

```text
BankAlJaziraDetector
```

Do not hard-code bank checks throughout the application.

`BankSmsRegistry.route` evaluates every registered adapter and returns a
`BankRoutingResult`:

| Result | Meaning | Ingestion |
|---|---|---|
| `Matched` | exactly one adapter detected the SMS | persist RawSms, parse with that adapter |
| `Ambiguous` | more than one adapter detected it | persist RawSms, direct `ambiguous_bank_route` review, parse with **neither** |
| `SuspectedBank` | no adapter detected it, but at least one reported a conservative suspicion | persist RawSms, direct `suspected_bank_sender` review, parse with **none** |
| `NotMatched` | no adapter detected or suspected it | not persisted |

Registration order never decides the bank; `Ambiguous` candidates are sorted by bank id.

Bank AlJazira detection is an exact allowlist after sender normalization
(`aljazira`, `jazirabank`, `bankaljazira`, `aljazirabank`, and the Arabic labels).
A promotional `-AD` suffix is stripped only when the remainder is already on that
list. A sender stem or body phrase can only raise `Suspected`; it never identifies
the bank by itself.

A REQUIRED review that still carries `ambiguous_bank_route` or `suspected_bank_sender` may record `user_selected_bank:<bankId>` for that RawSms only. Stored reprocessing then parses with that registered adapter and does not change detector rules. Queue refresh keeps the prefix when it replaces the other reasons. A different RawSms is not reclassified.

---

## 11. Bank Adapter Boundary

Bank-specific behavior should be grouped behind a bank parser/adapter concept.

Example:

```kotlin
interface BankSmsAdapter {
    val bank: Bank

    fun detect(sender: String, body: String): BankDetectionResult

    fun parse(input: SmsParseInput): ParseResult
}

interface BankMessageParser {
    val bank: Bank

    fun parse(input: SmsParseInput, normalized: NormalizedSms): ParseResult
}
```

The router owns bank detection: `detect` is evaluated once per processing attempt by
`BankSmsRegistry`. Once an adapter is selected, its parse pipeline trusts the route
and never re-checks the sender. Ingestion rejects (as `processing_error`) any parse
result whose event bank differs from the routed adapter's bank.

AlJazira `cardSmsChannel` is decided in the parser. Credit is an explicit credit
label, a due-amount line, or an unlabeled card line together with `الرصيد المتاح` /
available balance. Debit markers (`بطاقة مدى`, `خصمت من حساب`, standalone `mada`)
win over that balance line. A card number with none of those signals stays null.
The dashboard reads the stored channel and does not re-read the SMS.

Initial:

```text
BankAlJaziraMessageParser
```

Future:

```text
D360MessageParser
SNBMessageParser
```

Core domain services must not contain checks such as:

```kotlin
if (bank == BANK_ALJAZIRA) { ... }
```

unless the rule is genuinely domain-specific.

---

## 12. Classification

Do not classify only by exact full message template.

Classification should use evidence.

Example evidence for purchase:

```text
شراء
نقاط البيع
POS
Purchase
بطاقة
Card
Merchant
لدى
```

Result should include:

```text
message family
confidence
evidence/reasons
```

Suggested:

```kotlin
data class ClassificationResult(
    val family: MessageFamily,
    val confidence: Double,
    val evidence: List<String>
)
```

Classification is deterministic evidence resolution, never first-match order
(AlJazira: `AlJaziraClassificationRule` + `AlJaziraClassificationResolver`):

1. Evaluate every rule; each matching rule yields a candidate with an explicit specificity tier.
2. Keep the highest tier (security > account notice > balance notice > statement > named product > money movement > generic).
3. A single family in that tier wins; evidence records `outranked:<rule>` for the other families that matched.
4. Different families in that tier → `UNKNOWN` (`ambiguous_classification`, `candidate:<rule>`) → review.
5. An informational top tier (account notice, balance notice, statement) does not erase a strong money-movement candidate at the product or movement tier. Both stay visible as `UNKNOWN` with `candidate:<rule>` and `family:<family>` evidence → review. Security (OTP) is exempt and still wins. A higher financial tier still outranks a lower one, so a refund title that names the purchase it reverses stays `REFUND`. A generic fee line is not strong movement and does not create this collision.
6. That parsed family is the only text decision reconciliation uses. `InformationalMessagePolicy` auto-ignores `OTP`, `NON_FINANCIAL`, and `BALANCE_NOTICE` and leaves `UNKNOWN` reviewable. It does not read the SMS body, so statement or beneficiary wording cannot hide a purchase, transfer, or unresolved family. Review dismiss uses the same family, or the explicit `non_financial_or_informational_message` reason. Stale-link cleanup removes a posted transaction only when the parsed family is one of those three informational families.

Rule registration order must not change the result. Collision fixtures
(`testdata/bank_aljazira/**/collision_*.json`) pin the tie-breaks.

---

## 13. Field Extractors

Prefer focused extractors:

```text
AmountExtractor
CurrencyExtractor
MerchantExtractor
CardExtractor
AccountExtractor
DateTimeExtractor
CounterpartyExtractor
ReferenceNumberExtractor
```

An extractor may use:

1. generic aliases
2. Bank AlJazira-specific aliases
3. ordered fallback strategies

Avoid a single giant regex that extracts everything.

---

## 14. Validation

Parsing success must not be defined as "regex matched".

Validator examples:

- amount is positive
- currency is supported
- amount came from an amount label or strong context
- card last4 is not confused with amount
- account suffix is not confused with amount
- balance is not confused with transaction amount
- date is plausible
- required fields for the family are present

Suggested:

```kotlin
interface ParsedEventValidator {
    fun validate(event: ParsedEventDraft): ValidationResult
}
```

The validator is the final automatic-use firewall. `ParseFinalizer` is the only
producer of `ParseResult.Success`, and only when `ValidationResult.isAcceptableForAutomaticUse`
(no ERROR findings). A financial draft with any blocking finding is finalized as
`REVIEW_REQUIRED` with its event kept, never SUCCESS and never dropped.

| Code | Rule (financial families unless noted) |
|---|---|
| V-001…V-006 | Amount provenance: never a card/account suffix, balance, reference, date, or unlabeled number |
| V-007 | Multiple distinct transaction amounts (conflicting strong facts) |
| V-009 / V-010 | Amount required / must be positive |
| V-011 | Direction required and consistent with the family |
| V-012 | Confidence ≥ `AutomaticUsePolicy.minFinancialConfidence` (0.8) |
| V-013 / V-014 | Card / account suffix, when present, is exactly four ASCII digits (any family) |
| V-015 | `occurredAtLocal`, when present, is within the `AutomaticUsePolicy` window (any family) |
| V-016 / V-017 | Same account on both sides; purchase channel on a non-purchase family |

Validators check parse facts only. They never resolve ownership, pair transfers,
or decide `FinancialTransactionType` (enforced by `PackageDependencyRulesTest`).

---

## 15. Ownership Resolver

Suggested:

```kotlin
interface OwnershipResolver {
    fun resolveAccount(ref: AccountReference): OwnershipResolution
    fun resolveCard(ref: CardReference): OwnershipResolution
}
```

Sources:

- confirmed account records
- confirmed cards
- user corrections
- matched identifiers

Do not infer ownership only from the word `internal`.

---

## 16. Transaction Matcher

Purpose:

Combine related parsed events into a single real-world financial transaction.

Examples:

```text
outgoing transfer ↔ incoming transfer
purchase ↔ refund
bank debit ↔ credit card payment
```

Suggested service:

```kotlin
interface TransactionMatcher {
    fun findMatches(event: ParsedEvent): List<TransactionMatchCandidate>
}
```

Matching should be deterministic first.

Potential signals:

- amount
- currency
- event direction
- timestamp proximity
- account ownership
- account references
- bank
- transaction/reference IDs
- counterparty

---

## 17. Transaction Assembler

A separate service should produce the final `FinancialTransaction`.

Example:

```kotlin
interface TransactionAssembler {
    fun assemble(
        events: List<ParsedEvent>,
        context: ResolutionContext
    ): TransactionAssemblyResult
}
```

This prevents financial logic from being embedded in parsing code.

---

### 17.1 ParseStatus automation gate

`ParsedEvent.parseStatus` is a hard automation boundary:

| parseStatus | Automatic financial use |
|---|---|
| `SUCCESS` | eligible for ownership/matching/assembly |
| `NON_FINANCIAL` | ignored for transaction creation |
| `REVIEW_REQUIRED`, `PARTIAL`, `INVALID`, `UNSUPPORTED` | never auto-create, pair, or post; durable review instead |

Only an explicit user decision lifts the gate for one RawSms: a user correction, or a
review resolved `USER_FINANCIAL_TYPE` (restore from ignored, manual resolution).

Every persisted recognized-bank RawSms ends in a durable outcome: processed,
non-financial, review-required, or processing-error. When no usable ParsedEvent
exists, ingestion writes the review row directly (`IngestionReviewService`).

---

### 17.2 Transaction time zone

The parser still stores an offset-less `LocalDateTime`. `BankTransactionTimePolicy`
turns that wall clock into `FinancialTransaction.occurredAt`.

Bank AlJazira SMS times are Saudi civil time: `Asia/Riyadh` (UTC+3, no daylight-saving
time). That mapping lives only in `BankTransactionTimePolicy`. The handset zone is not
used for that bank. A bank without a fixed zone keeps
the zone id stored on the transaction at first assembly (`occurredAtZone`, schema 16).
Reassembly uses that stored zone, including a stale-pair heal, so a later device
timezone does not move the instant.

Dashboard period code keeps its own zone and does not branch on the bank.

---

## 18. Review Queue

Any unresolved situation should create a review item.

Possible reasons:

```text
UNKNOWN_MESSAGE_FAMILY
AMBIGUOUS_AMOUNT
UNKNOWN_ACCOUNT_OWNERSHIP
AMBIGUOUS_TRANSFER_MATCH
MISSING_REQUIRED_FIELD
CONFLICTING_FIELDS
LOW_CONFIDENCE_CLASSIFICATION
```

User resolution should be stored explicitly.

---

## 19. Persistence Strategy

Recommended entities conceptually:

```text
RawSmsEntity
ParsedEventEntity
AccountEntity
CardEntity
FinancialTransactionEntity
TransactionEventLinkEntity
ReviewItemEntity
UserCorrectionEntity
```

Do not finalize Room schema until domain models are agreed.

Room entities are persistence models, not domain models.

Use mappers between them.

---

## 20. Repository Interfaces

Domain-facing repository interfaces may include:

```kotlin
interface RawSmsRepository
interface ParsedEventRepository
interface AccountRepository
interface CardRepository
interface FinancialTransactionRepository
interface ReviewRepository
interface UserCorrectionRepository
```

Implementations belong in the data layer.

---

## 21. Application Use Cases

Suggested initial use cases:

```text
ScanHistoricalSmsUseCase
ProcessRawSmsUseCase
DetectAccountsUseCase
ConfirmAccountOwnershipUseCase
PreviewImportUseCase
ConfirmImportUseCase
ResolveReviewItemUseCase
ObserveTransactionsUseCase
ObserveReviewCountUseCase
```

Use cases coordinate services.

They should not parse bank-specific text directly.

---

## 22. UI Architecture

Jetpack Compose may be used.

Presentation layer:

```text
Screen
  ↓
ViewModel
  ↓
UseCase
  ↓
Domain
```

ViewModels should not contain:

- regex
- ownership rules
- transfer matching
- bank identification logic
- financial calculation rules

Presentation consumes prepared application facts. `DashboardOverview` (from
`DashboardProjection`) carries, besides section totals and involvement indexes:

| Fact | Field | Rule owner |
|---|---|---|
| Row card last4 (card container, else primary SMS card ref) | `transactionFacts[id].primaryCardLast4` | `DashboardTransactionFactsBuilder` |
| Effective type (loan-attributed rows → `LOAN_REPAYMENT`) | `transactionFacts[id].effectiveType` | `DashboardTransactionFactsBuilder` over `LoanRepaymentAttribution` involvement |
| SAR equivalent (foreign amount × applied rate) | `transactionFacts[id].sarEquivalent` | `DashboardTransactionFactsBuilder` / `ForeignPurchaseSarConverter` |
| Owned account container ids for list filtering | `ownedAccountContainerIds` | projection context |

`DashboardViewModel` maps these to UI models (labels, locale formatting, direction styling)
and `MasroofRoot` only passes them on. Neither constructs or parses financial container ids
nor calls conversion/classification helpers; `PackageDependencyRulesTest` enforces this for
every presentation `*ViewModel.kt` and for `presentation/navigation`.

### 22.1 Dashboard read model

`DashboardProjectionBuilder` is composition only: it loads one `DashboardProjectionContext`
(registries, scoped evidence, displayed period transactions with in-memory rates, SAR
equivalents, debit-card scope, locale) and hands it to the section projections —
`AnalysisDashboardProjection` (summary, merchants, daily trend), `AccountsDashboardProjection`,
`CardsDashboardProjection` (statement window, facilities, debit spend) and
`CommitmentsDashboardProjection` (loans, commitments, statement-settling payments). A section
owns only the extra reads its rules need; shared inputs are never re-read. Specialist
`*Builder` / `*Calculator` objects stay authoritative for the rules.

The dashboard is a scoped read model over persisted facts. `DashboardService.loadProjection`
reads the selected salary period's `FinancialTransaction`s and hands them to
`DashboardProjectionBuilder`, which obtains parsed/raw evidence only through
`DashboardEvidenceSource` (`DashboardEvidenceScope` in production):

| Evidence | Query |
|---|---|
| Linked evidence of displayed transactions (period, statement window, commitment sources, statement-settling payments) | `FinancialTransactionRepository.listRawSmsIdsForTransactions` → `ParsedEventRepository.listByRawSmsIds` → `RawSmsRepository.getByIds` |
| Statement cycles | `listCardStatementFacts` |
| Credit-card identity (newest row per card) | `listLatestCreditCardRowFacts` |
| Available-balance snapshot | `listLatestCreditCardAvailableBalanceFacts(periodEnd)` |
| Loans | `listFinancingInstallmentFacts` |
| Historical merchant FX rates | `listExchangeRateFacts` |
| Debit-card classification / linked account | `listFirstDebitCardFacts(registry last4s)` |
| Credit-card payments settling an in-period due | `listByTypesOccurredSince(CREDIT_CARD_PAYMENT, earliest due update)` |

Rules:

- No `ParsedEventRepository.listAll()` and no per-row `RawSmsRepository.getById` on the
  normal load path. Batch queries chunk their `IN (...)` lists (`RoomBatch`).
- A history-fact query may return a superset of the rows its calculator rule can use,
  never a subset; records reach calculators distinct and ordered by event id, so
  "first/last matching row" rules behave as with a whole-history scan.
- A new calculator rule that needs history not linked to a displayed transaction adds an
  explicit fact query here; it must not widen the load to whole history.
- Normal loads are read-only: no repository writes during projection. Resolved exchange
  rates are applied to the displayed transactions in memory
  (`AppliedExchangeRateSyncer.applyInMemory`); a rate already persisted always wins.
- `application/transaction/ExchangeRateEnrichmentWorkflow` is the only writer of
  `appliedExchangeRate` / `exchangeRateSource`. It resolves pending foreign transactions
  with the dashboard's resolver and evidence rules, resolves everything before writing,
  and runs after live stored-SMS processing, after each historical batch, after bulk
  reparse, and as best-effort startup background maintenance. Unresolved rows (e.g. no
  network for a market rate) stay pending for the next run.
- FX persistence is an immutable historical pair. The first complete
  `(appliedExchangeRate, exchangeRateSource)` stays together. Enrichment and transaction
  rewrites cannot replace or clear either column of a complete pair. A legacy row with
  only one half is repaired by writing both columns from the same new resolution, so an
  old rate is never stored next to a newly inferred source. A later market quote does not
  move dashboard totals. User correction of a frozen pair is a separate workflow and is not
  part of enrichment or dashboard load.

---

## 23. Background Processing

Historical scans should run off the main thread.

New SMS processing should be safe even if:

- the app UI is closed
- processing is retried
- the same SMS is delivered twice

The pipeline must be idempotent.

Live processing runs in WorkManager:

| Concern | Contract |
|---|---|
| Work input | `rawSmsId` only (`LiveSmsProcessingWorker.KEY_RAW_SMS_ID`); never body or OTP text |
| Duplicates | unique work per rawSmsId with `ExistingWorkPolicy.KEEP`; capture dedupe returns `Duplicate` without scheduling |
| Retry | exponential backoff; `Result.retry()` for processing failures, exceptions, and `DerivedIncomplete` (ownership, reconciliation, review refresh) until `MAX_ATTEMPTS`. Parse failures keep their `processing_error` review. The final derived failure records that review and a `processing_retry` row. If that write fails, the worker returns `Result.retry()` instead of stopping. Exchange-rate enrichment failure stays `Result.success()` |
| Permanent failure | missing input or `raw_sms_not_found` → `Result.failure()`. A final derived failure becomes `Result.failure()` only after the recovery marker is saved |
| Cancellation | `CancellationException` propagates; captured evidence stays and is processed by the next run |
| Process death | startup sweep `LiveSmsIntake.schedulePendingProcessing()` reschedules, per message, `RawSmsRepository.listIdsAwaitingProcessing()` (no ParsedEvent and no review row), REQUIRED `processing_error` reviews, and `processing_retry` rows whose `mode` is `LIVE`. Rows whose `mode` is `HISTORICAL_BATCH` enqueue exactly one `HistoricalDerivedRecoveryWorker`, including rows that already have a review. `USER_NON_FINANCIAL` stays closed. Other resolved reviews stay resolved and remain recoverable through the retry row. A successful live retry clears that row and auto-resolves a processing-error review. A successful historical recovery clears the whole historical retry set in one transaction |
| Wiring | `MasroofApplication.workManagerConfiguration` registers `AppContainer.workerFactory`, a `DelegatingWorkerFactory` over `LiveSmsProcessingWorker.Factory`, `HistoricalDerivedRecoveryWorker.Factory`, and `ParsedEventFactsBackfillWorker.Factory`; other workers fall back to the default factory |

### 23.1 Startup maintenance policy

Maintenance has blocking/background policy (`application/maintenance/MaintenanceRequirement`).
Each task is classified, not moved wholesale to the background:

| Requirement | Meaning | Startup behavior |
|---|---|---|
| `BLOCKING` | stored data displays incorrectly until the task finishes | the launch spinner (`AppContainer.awaitStartupMaintenance`) waits for it |
| `BACKGROUND` | stored data is already correct to display; the task only refreshes it | handed to retryable WorkManager work; the app opens immediately |

- Schema facts backfill (re-parse of the stored RawSms backlog after a schema upgrade) is
  classified per Room version in `SchemaFactsBackfillPolicy`. A pending range is
  `BLOCKING` if any version in it is (v10, v11: parse-fact columns dashboard and
  reconciliation rules read) or is undeclared; otherwise `BACKGROUND`. Every schema version
  must be declared (`SchemaFactsBackfillPolicyTest`).
- `StartupMaintenance.runBlockingPhase` runs a `BLOCKING` backfill inline. If rows fail,
  financial UI stays gated and the startup screen offers retry; blocking work is never
  downgraded to background merely to release the UI.
- `ParsedEventFactsBackfillWorker` runs the same `ParsedEventFactsBackfillCoordinator`
  (unique work, `KEEP`, exponential backoff, `MAX_ATTEMPTS`). The coordinator serializes
  runs, records the schema version only after a run with no failed rows, and turns a thrown
  run into `INCOMPLETE`, so failed rows stay eligible for the next attempt or launch.
  Re-parsing is idempotent.
- After any backfill run the coordinator emits `MaintenanceCompletionSignal`.
  `DashboardViewModel` and `ReviewViewModel` reload on it once they have loaded, so a
  background backfill refreshes open screens.
- Exchange-rate enrichment and the pending-SMS sweep are `BACKGROUND` and run after the
  blocking phase; the dashboard already shows resolved rates in memory (§22.1).

---

## 24. Error Handling

Use explicit results instead of exceptions for expected parsing failures.

Example:

```kotlin
sealed interface ParseResult {
    data class Success(val event: ParsedEvent) : ParseResult
    data class Partial(val draft: ParsedEventDraft, val reasons: List<String>) : ParseResult
    data class Unsupported(val reason: String) : ParseResult
    data class Invalid(val reasons: List<String>) : ParseResult
}
```

Unknown format is not an exceptional crash condition.

---

## 25. Testing Strategy

### Unit tests
Required for:

- normalization
- classifiers
- each extractor
- validation
- ownership resolution
- transfer rules
- transaction matching

### Fixture tests
Every real Bank AlJazira SMS sample should become a fixture test.

### Integration tests
At minimum:

```text
RawSms
→ parser
→ ParsedEvent
→ ownership
→ matcher
→ FinancialTransaction
```

### UI tests
Only after domain pipeline is stable.

---

## 26. Fixture-Driven Development

Folder example:

```text
testdata/
  bank_aljazira/
    purchase/
    transfer/
    refund/
    bill_payment/
    withdrawal/
    non_financial/
```

Each fixture needs:

```text
raw message
expected family
expected extracted fields
expected review behavior
```

When a production SMS fails:

1. add sanitized fixture
2. write failing test
3. fix parser
4. retain regression test forever

---

## 27. Adding a New Bank Later

Adding another bank should roughly require:

```text
1. sender detection
2. fixture dataset
3. bank message classifier/rules
4. bank field aliases/extractors
5. tests
```

Every adapter must pass the shared contract (`bank/contract/BankSmsAdapterContract`)
with its own `BankSmsAdapterContractSamples`: positive and known-negative senders,
at least one fixture-backed financial message (parses `SUCCESS` with an amount) and
one non-financial message, and unsupported/ambiguous messages that never parse
`SUCCESS`. The registry contract also requires every sample to be claimed by exactly
one adapter regardless of registration order.

It must not require changes to:

- ownership concepts
- transaction model
- self-transfer rules
- credit-card payment meaning
- dashboard business logic

---

## 28. Initial Implementation Order

```text
1. Core models
2. Domain rules/tests
3. Raw SMS storage
4. Historical scanner
5. Bank AlJazira detection
6. Normalizer
7. Classifier
8. Field extractors
9. Validator
10. ParsedEvent storage
11. Account discovery
12. Ownership resolver
13. Transaction matcher
14. Review queue
15. Final Room schema
16. Onboarding
17. Dashboard
```

Do not start with UI polish.

---

## 29. Prohibited Architecture Patterns

Avoid:

- giant `SmsParser`
- giant `RegexUtils`
- `TransactionManager`
- bank-specific logic in ViewModels
- UI-driven financial rules
- direct Room entities throughout the app
- silently swallowing parse failures
- guessing missing amounts
- using the first number in a message as amount
- treating "internal transfer" as self-transfer
- treating all incoming transfers as income
- treating card payment as expense

---

## 30. Success Criterion

A developer or AI coding agent should be able to modify the Bank AlJazira parser without touching the financial domain.

Likewise, the financial domain should be testable without Android, SMS APIs, Room, or Compose.
