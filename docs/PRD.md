# Masroof v2 — Product Requirements Document (PRD)

> Historical product record. The phases below describe the rewrite as it was specified. They are not an unshipped backlog. Live implementation rules are in `AGENTS.md`.

## 1. Product Summary

Masroof is a personal Android financial tracking application that reads banking SMS messages on the user's device and converts them into structured financial events and transactions.

The application is for personal use and is not designed for public distribution.

Masroof must minimize manual work. After the initial setup, the expected experience is:

1. The bank sends an SMS.
2. Masroof reads it.
3. Masroof identifies the bank and message type.
4. Masroof extracts the financial fields.
5. Masroof determines what the event means financially.
6. Masroof matches related events when needed.
7. Masroof stores the resulting transaction.
8. The user sees an updated financial picture.

The user should only be asked to intervene when Masroof cannot determine something with sufficient confidence.

---

## 2. Clean Rewrite Requirement

Masroof v2 is a clean rewrite.

The previous application implementation must be treated as reference-only.

The new implementation must NOT preserve legacy:

- parsers
- regex collections
- transaction models
- repositories
- Room entities
- ViewModels
- UI screens
- business logic
- tests
- fake data
- utility classes
- architecture decisions

unless a specific item is intentionally reviewed and explicitly approved for reuse.

The codebase must not contain unused legacy files after the rewrite.

### Mandatory rule

> Do not refactor the legacy architecture into Masroof v2. Rebuild Masroof v2 from the new specifications.

---

## 3. Product Goal

Create a highly reliable personal financial ledger that automatically understands banking SMS messages and maintains an accurate view of:

- expenses
- income
- transfers
- bank accounts
- cards
- liabilities
- refunds
- bill payments
- cash movement
- transfers between owned accounts

The system must prioritize correctness over aggressive automation.

### Core reliability rule

> A message that cannot be understood safely must go to review instead of being silently converted into a wrong transaction.

---

## 4. Product Principles

### 4.1 SMS is an input event, not necessarily a transaction

One SMS may represent one financial event.

Multiple SMS messages may describe the same real-world transaction.

Example:

- Bank AlJazira sends an outgoing transfer SMS.
- D360 sends an incoming transfer SMS.

These are two SMS events but one financial transfer.

---

### 4.2 Bank terminology is not financial meaning

If Bank AlJazira says "internal transfer", it means the transfer happened inside Bank AlJazira.

It does NOT mean the transfer is between the user's own accounts.

Masroof must determine ownership separately.

---

### 4.3 Transfers between owned accounts are not expenses

Example:

- Bank AlJazira account 3001 → Bank AlJazira account 3002
- Bank AlJazira → D360
- Bank AlJazira → STC Bank

If both source and destination accounts belong to the user:

- Expense = 0
- Income = 0
- Net worth change = 0

---

### 4.4 Credit card payments are not expenses

The purchase is the expense.

Paying the credit card balance is a liability settlement and must not create a second expense.

---

### 4.5 Incoming transfers are not automatically income

An incoming transfer may be:

- salary
- reimbursement
- gift
- family transfer
- refund
- internal transfer
- temporary money movement
- other income

Masroof must not classify every incoming transfer as income automatically.

---

### 4.6 Prefer review over silent error

If confidence is insufficient:

`REVIEW_REQUIRED`

must be used.

Silent wrong parsing is considered a critical defect.

---

## 5. Initial Scope

Masroof v2 will initially support one bank only:

**Bank AlJazira**

The architecture must support additional banks later without changing core domain logic.

The first release must prove that Bank AlJazira messages can be handled accurately before adding D360, SNB, Al Rajhi, or other banks.

---

## 6. User Experience

## 6.1 First Launch Flow

The initial application flow:

```text
Welcome
  ↓
Explain SMS access
  ↓
Request READ_SMS
  ↓
Request RECEIVE_SMS
  ↓
Scan existing SMS
  ↓
Detect banking senders
  ↓
Detect Bank AlJazira messages
  ↓
Analyze messages
  ↓
Discover accounts/cards
  ↓
Ask user to confirm ownership
  ↓
Show import preview
  ↓
Review uncertain items
  ↓
Confirm import
  ↓
Dashboard
```

---

## 6.2 SMS Permissions

Masroof requires access to:

- existing SMS messages
- newly received SMS messages

The app should explain why access is required before requesting Android permissions.

The app is intended for personal installation.

---

## 6.3 First Historical Scan

On first setup, the user may choose:

- Last month
- Last 3 months
- Last year
- All messages

Recommended default:

**Last year**

The scan must not immediately create final transactions.

It first creates parsed events and presents a preview.

---

## 6.4 Bank and Sender Detection

Masroof should detect known bank senders automatically.

For v2 initial scope:

- detect Bank AlJazira sender(s)
- ignore unrelated SMS

Unknown sender handling may be added later.

---

## 6.5 Account and Card Discovery

Masroof should attempt to discover account/card identifiers from historical messages.

Examples:

```text
Bank AlJazira
- Account ****3001
- Account ****3002
- Credit Card ****7271
```

The user should confirm:

- whether the account/card belongs to them
- optional display name
- account/card type where needed

Ownership confirmation must be stored.

---

## 6.6 Import Preview

Before creating final transactions, show:

```text
Recognized successfully
Needs review
Ignored as non-financial
Unrecognized
```

Example:

```text
361 recognized
19 need review
98 non-financial
6 unrecognized
```

The user should be able to inspect uncertain items before confirming the first import.

---

## 6.7 Review Queue

The review queue must contain messages/events that require user action.

The user may correct:

- transaction type
- amount
- merchant
- account
- card
- counterparty
- ownership
- direction
- category
- whether the message is financial

User corrections should be stored so they can later improve matching and classification.

---

## 6.8 Daily Operation

After onboarding:

```text
New SMS
  ↓
Known bank sender?
  ↓
Normalize
  ↓
Classify
  ↓
Extract fields
  ↓
Validate
  ↓
Resolve ownership
  ↓
Match related events
  ↓
Create/update transaction
  ↓
Categorize
  ↓
Store
```

If confidence is too low:

```text
Review Queue
```

The app should not interrupt the user with a popup for every uncertain message.

---

## 7. Main Product Areas

### 7.1 Dashboard

The dashboard should eventually show:

- current month spending
- current month income
- account balances when derivable
- recent transactions
- category summaries
- review queue count

Dashboard work is not part of the first implementation phase.

---

### 7.2 Transactions

Each final financial transaction should represent the real-world financial meaning, not merely an SMS.

Examples:

- Purchase
- Bill payment
- Refund
- Incoming transfer
- Outgoing transfer
- Self-transfer
- Credit card payment
- Cash withdrawal
- Fee

---

### 7.3 Accounts

Masroof must support:

- current accounts
- savings accounts
- wallets
- credit cards
- debit cards
- investment accounts later
- other owned financial containers later

---

### 7.4 Ownership

Every known account/card should support ownership state:

- OWNED
- EXTERNAL
- UNKNOWN

Ownership is a first-class domain concept.

---

## 8. Functional Requirements

### FR-001 — Read historical SMS
Masroof must be able to scan previously received SMS messages.

### FR-002 — Receive new SMS
Masroof must detect newly received SMS messages while installed.

### FR-003 — Preserve raw SMS
Masroof must retain the raw SMS record needed for traceability.

### FR-004 — Detect Bank AlJazira
Masroof must identify Bank AlJazira messages independently from unrelated messages.

### FR-005 — Normalize text
Masroof must normalize formatting differences before parsing.

### FR-006 — Classify messages
Masroof must classify supported message families.

### FR-007 — Extract fields
Masroof must extract structured fields such as amount, account, card, date, merchant, and counterparty.

### FR-008 — Validate results
Masroof must validate extracted values before creating a financial transaction.

### FR-009 — Resolve ownership
Masroof must identify whether referenced accounts belong to the user.

### FR-010 — Match related SMS events
Masroof must support matching multiple SMS events that represent one real transaction.

### FR-011 — Detect self-transfers
Transfers between owned accounts must not become income or expense.

### FR-012 — Separate intra-bank from self-transfer
Bank network type and ownership relationship must be represented separately.

### FR-013 — Review uncertain events
Uncertain results must enter a review queue.

### FR-014 — Avoid duplicate imports
Historical scans and live SMS ingestion must not create duplicate raw SMS records or duplicate final transactions.

### FR-015 — Store corrections
User corrections must be stored separately from raw source data.

---

## 9. Non-Functional Requirements

### Reliability
Silent wrong classification should be treated as a critical defect.

### Maintainability
Bank-specific parsing must not leak into core financial domain logic.

### Testability
All parsing and domain rules must be testable without Android UI.

### Local-first
The application should operate locally on the device for core functionality.

### Privacy
Raw banking SMS data should remain local unless the user explicitly chooses otherwise in a future feature.

### Performance
Initial SMS scanning should be performed efficiently and must not freeze the UI.

### Idempotency
Re-running the same scan should not create duplicates.

---

## 10. Initial Supported Message Families

The exact list will be maintained in `PARSING_SPEC.md`.

Initial Bank AlJazira families include, where sample messages exist:

- card purchase
- POS purchase
- online purchase
- incoming transfer
- outgoing transfer
- intra-bank incoming transfer
- intra-bank outgoing transfer
- bill payment
- refund
- cash withdrawal
- card-related events
- fees where detectable
- non-financial bank notices

New families may be added only through documented parsing fixtures and tests.

---

## 11. Out of Scope for Initial Rewrite

Do not implement yet:

- multiple banks
- cloud sync
- public distribution
- Google Play requirements
- family/shared accounts
- full accounting system
- investment valuation
- AI-first parsing
- OCR
- manual bank integrations/APIs
- complex budgeting
- advanced analytics
- predictive insights
- conversational AI

These may be considered later.

---

## 12. Release Milestones

### Phase 0 — Specification
- PRD
- Domain model
- Architecture
- Parsing specification
- fixture dataset

### Phase 1 — Core Domain
- account
- card
- raw SMS
- parsed event
- financial transaction
- ownership
- transfer model

### Phase 2 — SMS Ingestion
- historical scan
- live SMS receiver
- raw SMS persistence
- duplicate prevention

### Phase 3 — Bank AlJazira Parsing
- normalization
- classifier
- field extractors
- validator
- parsing tests

### Phase 4 — Ownership
- account discovery
- user ownership confirmation
- ownership resolution

### Phase 5 — Transaction Matching
- outgoing ↔ incoming transfer
- self-transfer detection
- duplicate-event matching

### Phase 6 — Review Queue
- uncertain event review
- correction persistence

### Phase 7 — Persistence
- final Room schema
- repositories

### Phase 8 — Onboarding
- permission flow
- scan flow
- account confirmation
- import preview

### Phase 9 — Dashboard
- recent activity
- spending
- income
- review queue

### Phase 10 — Categorization
- rule-based categorization
- optional local AI fallback later

---

## 13. Definition of Done for Bank AlJazira v2

A Bank AlJazira parsing release is acceptable when:

- all known fixture messages have tests
- no known fixture silently produces the wrong amount
- no owned-to-owned transfer is counted as income or expense
- an incoming intra-bank transfer from another person is not misclassified as self-transfer
- credit card payment is not counted as a new expense
- re-import does not duplicate transactions
- unsupported messages enter review or are safely ignored
- old application architecture is absent from production source code

Target quality:

```text
Recognized correctly: very high
Needs review: acceptable
Silent wrong result: 0 for known fixtures
```
