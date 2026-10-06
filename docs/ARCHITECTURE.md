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
HistoricalSmsScanner
   ↓
RawSms
   ↓
RawSmsRepository
```

### New messages

```text
BroadcastReceiver
   ↓
IncomingSmsHandler
   ↓
RawSms
   ↓
RawSmsRepository
```

Both flows must converge into the same processing pipeline.

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
| `NotMatched` | no adapter detected it | not persisted |

Registration order never decides the bank; `Ambiguous` candidates are sorted by bank id.

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

---

## 23. Background Processing

Historical scans should run off the main thread.

New SMS processing should be safe even if:

- the app UI is closed
- processing is retried
- the same SMS is delivered twice

The pipeline must be idempotent.

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
