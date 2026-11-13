# Additional banking senders

The production pattern store installs five additional declarative role profiles
(adopted from PR #341, banks only):

| Bank identity | Exact sender |
|---|---|
| `BANK_SNB` | `SNB-AlAhli` |
| `BANK_BARQ` | `barq app` |
| `BANK_EMIRATES_NBD` | `EmiratesNBD` |
| `BANK_SAB` | `SAB` |
| `BANK_ALINMA` | `alinma` |

They use the existing `MessagePatternSmsAdapter`, `PatternFactInterpreter`,
validation, ownership, reconciliation and durable-retry paths. There is no
parallel parser and no database change.

## Opt-in rule capabilities

These observation keys change extraction only for rules that list them, and only
when the message title contains one of the listed phrases:

- `amount.title`: `<title> ب|بمبلغ 100 SAR` on the first line.
- `amount.standalone`: a bare `100 SAR` line.
- `label.suffix`: `value : label` lines (EmiratesNBD fee and financing layouts).
- `network.sourceBankRole` / `network.destinationBankRole`: extra bank-name labels.

## Shared grammar (all banks)

- The currency may touch the amount (`SAR100`, `100SAR`, `SR100`, `ر.س100`);
  `100SARX` is still rejected.
- A role label may touch its digits (`بمبلغ120`) but never a longer word.
- Masked references `XX1234`, `**1234`, `1234*` and `(1234)` read the last four
  digits. A full IBAN or account number is never shortened into an account suffix.
- The Arabic inline purchase clause (`شراء عبر نقاط البيع` / `باستخدام بطاقة …`)
  is segmented like the English one.

`ExistingBankParseSnapshotTest` freezes every existing AlJazira/STC fixture outcome
so these shared changes cannot alter current messages.

## Deliberately not adopted

- Explicit default account / currency setup sheet.
- Signed (negative) available balances: they stay in review.
- Card-funded bill posting and card-bill spending.
- Cross-bank destination-suffix transfer bridging.

Messages that need any of these, or that lack a local account, remain reviewable.
Support for a sender does not imply automatic posting of every message it sends.

## Validation

Fixtures under `testdata/multibank` contain fictional names, values, dates and
identifiers. Each bank participates in the shared adapter contract and the
multi-adapter routing checks. `FiveBankSmsTest` checks family, amount, channel,
review boundaries and informational dispositions. An optional local-only corpus
can be supplied with `MASROOF_PRIVATE_CORPUS`; it reports counts without bodies.
Never upload that corpus or its financial contents.
