# Masroof

**Languages:** **English** | [العربية](README_AR.md)

Masroof is a local-first Android personal finance app that converts supported banking SMS messages into a structured financial ledger.

The project is designed around **financial correctness, traceability, and recovery**. Original SMS evidence is preserved, parsing is separated from transaction posting, ambiguous cases stay reviewable instead of being guessed, and failed financial processing remains retryable rather than disappearing silently.

## What Masroof does

Masroof can:

- capture supported banking SMS from live delivery and historical inbox scans;
- classify financial and non-financial messages;
- create ledger movements for purchases, income, transfers, refunds, fees, cash withdrawals, card payments, and other supported transaction families;
- track owned accounts, cards, and loans;
- reconcile the two SMS legs of a transfer without double-counting the money;
- keep uncertain evidence in Review;
- calculate account, card, merchant, commitment, and financial-period summaries;
- preserve explicit user corrections and review decisions;
- apply dated foreign-exchange evidence without borrowing a future exchange rate;
- create encrypted portable backups and safely restore them;
- compare the SMS-derived ledger with a user-supplied **bank-account statement** through a read-only reconciliation workflow.

Bank-specific parsing is currently centered on **Bank AlJazira**. The architecture supports additional bank adapters, but a bank is not considered supported until sender detection, parsing fixtures, and adapter contract tests exist.

## How it works

The core financial pipeline is:

```text
Incoming SMS
    ↓
RawSms
    ↓
ParsedEvent
    ↓
Ownership / attribution
    ↓
Reconciliation
    ↓
FinancialTransaction or Review
    ↓
Dashboard read model
```

### Live SMS path

```text
IncomingSmsReceiver
→ LiveSmsIntake
→ CaptureBankSmsUseCase
→ durable RawSms
→ WorkManager
→ LiveSmsProcessingWorker
→ ProcessStoredSmsUseCase
→ parse
→ ownership
→ reconciliation
→ review / ledger
```

Only the RawSms identifier is passed to WorkManager. The SMS body itself is not placed in work input.

### Historical SMS path

Historical scanning is intentionally separate:

```text
HistoricalSmsScanner
→ HistoricalSmsBatchProcessor
→ parse each SMS
→ one ownership / reconciliation / review pass for the batch
```

Historical import does not enqueue a live worker for every old SMS.

## Financial integrity rules

Masroof treats the following as architectural invariants:

- **RawSms is immutable evidence.** Later parsing, matching, and user corrections do not replace the original message.
- A captured recognized financial SMS must eventually become **posted, required for review, or durably retryable**.
- A stored ParsedEvent does not by itself mean the financial movement was posted successfully.
- Reconciliation failures are not silently reported as success.
- One RawSms may link to at most one posted financial movement.
- A verified self-transfer is one movement, not an expense plus income.
- Distinct same-amount transfers stay distinct unless the evidence proves they are the same movement.
- Explicit user decisions take precedence over later automation.
- Different currencies are never silently added together.
- Dashboard code consumes persisted structured facts; it does not re-parse bank-specific SMS wording.

## Accounts, cards, and identity

Financial identities are bank-qualified.

Examples:

```text
account:BANK_ALJAZIRA:3001
card:BANK_ALJAZIRA:7271
```

The same last four digits at two different banks are not treated as the same account or card.

Bank-account cash flow and credit-card activity are also kept conceptually separate. For example, a refund credited to a current account affects bank cash, while a credit-card refund reduces card activity/liability and does not create bank cash.

## Transfers

Transfer matching is conservative. Evidence may include:

- a shared transaction reference;
- bank-qualified account endpoints;
- bank-local transaction time;
- SMS receipt time when appropriate;
- exact amount and currency;
- ownership evidence.

When more than one candidate is equally plausible, Masroof keeps the movement reviewable instead of guessing.

## Foreign exchange

Foreign-currency conversion is based on evidence available **at or before the transaction time**. A later merchant exchange-rate SMS is not applied to an earlier purchase.

Persisted rate/source pairs are not silently rewritten. Historical corrections that require changing an already stored rate use an explicit remediation workflow.

## Backup and restore

Portable backups are encrypted and authenticated.

The restore path is designed to fail safely:

- malformed or oversized archives are rejected before the live database is replaced;
- legacy plaintext backups require explicit acknowledgement;
- interrupted restores recover to either the valid original database or the fully installed replacement;
- Room migrations and integrity checks run before a restored database becomes authoritative;
- abandoned import staging is cleaned up.

Android cloud backup is disabled for the application's structured financial state. Portable backup is an explicit user action.

## Bank-statement reconciliation

Masroof includes an optional read-only comparison between the posted SMS-derived ledger and a user-supplied **bank-account statement**.

The current supported contract is a validated canonical CSV with:

- explicit bank and account identity;
- an explicit coverage period;
- debit/credit direction;
- exact decimal amount and currency;
- booking date/time;
- optional transaction reference.

Comparison results include:

- **MATCHED** — statement and ledger agree;
- **STATEMENT_ONLY** — the bank statement contains a movement Masroof did not post;
- **LEDGER_ONLY** — Masroof contains a posted movement absent from the supplied statement period;
- **AMBIGUOUS** — more than one safe match is possible;
- **UNSUPPORTED** — available evidence is insufficient for a safe comparison.

The comparison does **not** automatically create, delete, hide, or reclassify financial transactions.

This feature currently targets **bank-account statements**, not credit-card statement reconciliation.

## Privacy model

Masroof is local-first.

- SMS and financial data are processed on-device.
- Statement content is read locally and kept transient for comparison.
- Raw statement text is not uploaded by the reconciliation workflow.
- Logs must not contain SMS bodies, account numbers, PINs, passwords, tokens, or other financial secrets.
- GitHub update credentials are stored through Android Keystore-backed protection.

## Project structure

```text
app/src/main/kotlin/com/baraa/masroof/
├── application/   orchestration and application workflows
├── bank/          bank adapters and bank-specific parsing
├── core/          shared low-level primitives
├── data/          Room, preferences, and repository implementations
├── domain/        financial models, rules, matching, and repository contracts
├── parsing/       parser-neutral parsing models and validation
├── presentation/  Compose UI and ViewModels
└── sms/           Android SMS boundary and SMS-domain utilities
```

Important supporting locations:

```text
app/schemas/                         Room schema history
app/src/test/                        JVM / Robolectric tests
app/src/androidTest/                 Android instrumentation tests
app/src/test/resources/testdata/     parser and financial regression fixtures
docs/                                architecture and historical design records
scripts/                             CI/release/process-death helpers
.github/workflows/                   CI and release workflows
```

## Architecture boundaries

The dependency direction is intentionally constrained:

```text
presentation
    ↓
application
    ↓
domain / parsing / data / sms / bank
```

Bank-specific wording belongs in `bank/*` parsers. Dashboard code must not import bank parsers or re-interpret SMS bodies.

For the complete engineering rules, see [AGENTS.md](AGENTS.md).

## Database migrations

Committed Room migrations and historical schema files are part of compatibility and must not be removed merely because they target old versions.

A schema change requires:

1. a Room migration;
2. entity/mapper updates;
3. parser population when applicable;
4. a migration test;
5. backfill when existing rows need new structured facts before they can be displayed safely.

## Testing

Useful local commands:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew detekt
./gradlew :app:assembleDebug
```

The CI suite also runs Android emulator coverage, including lifecycle/restart scenarios. True process-death journeys use:

```bash
bash scripts/m13-process-death.sh
```

The golden-ledger and financial-invariant suites provide regression protection for money totals, RawSms links, review state, retries, transfer neutrality, currency separation, and replay behavior.

## Development guidelines

Before changing financial behavior:

1. reproduce the case with a fixture or deterministic test;
2. preserve RawSms evidence;
3. keep bank wording inside the bank parser;
4. do not bypass parse-status or ownership gates;
5. keep reconciliation idempotent;
6. preserve user decisions;
7. include migration/backfill coverage if persistence changes;
8. run targeted tests plus the full relevant CI suite.

Do not remove a file simply because its name looks old. Room migrations, backup compatibility, regression fixtures, process-death hooks, and saved-navigation compatibility may intentionally retain historical code.

## Documentation

- [AGENTS.md](AGENTS.md) — current engineering and architecture rules.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — deeper architecture record.
- [docs/DOMAIN.md](docs/DOMAIN.md) — financial domain notes.
- [docs/PARSING_SPEC.md](docs/PARSING_SPEC.md) — parsing behavior and contracts.
- [docs/CI.md](docs/CI.md) — CI details.
- [docs/RELEASE.md](docs/RELEASE.md) — release process.
- [docs/PRD.md](docs/PRD.md) and [docs/REWRITE_DECISIONS.md](docs/REWRITE_DECISIONS.md) — historical product/rewrite records.

## Repository status

The M0–M19 financial integrity and reliability roadmap has been implemented on `main`. Ongoing work should preserve those invariants and treat cleanup as behavior-preserving unless a separate feature or defect fix explicitly changes product behavior.
