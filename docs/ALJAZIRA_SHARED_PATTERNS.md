# AlJazira shared-pattern cutover

AlJazira now enters the same configured adapter, pattern store, revision controls,
pending application and financial write guard as learned banks. Startup installs
its bundled definitions without asking the user to teach or approve them again.
No database migration, backfill, posted-evidence reparse or second intake is added.

## Smallest compatible representation

The existing generic role matcher remains useful: it handles reordered and optional
role lines, inline purchase wording, bank aliases and explicit observations. Reducing
that behavior to a finite set of whole-message examples would narrow support.

Configuration schema 3 therefore adds a closed `roleProfile` matching form to
`SavedMessagePattern`, alongside learned typed-slot matching. Each role pattern owns
one immutable rule and its network vocabulary, with the same explicit fact contract,
id, revision and enabled state as other patterns. The existing strict profile codec
and generic extractors execute it; no old bank adapter is used as a financial fallback.
The old standalone `.rules.json` asset is removed. Schemas 1 and 2 remain readable.

The bundle contains 13 declarations across 11 families, not 13 message layouts:

| Declarations | Retained roles and observations |
| --- | --- |
| Purchase, refund, withdrawal | Principal/currency, card/account, merchant, debit/credit, purchase channel, balance, foreign amount, fee and FX |
| Fee | Principal/currency and account roles |
| Transfer in/out | Source/destination roles and banks, explicit network evidence, counterparties, references; salary wording on incoming only |
| Card payment, bill payment | Source/card roles, biller and biller code |
| Financing installment | Source, counterparty, remaining balance and personal/auto/mortgage observations |
| Balance, notice, statement, loyalty | Informational authority; statement balance and due-date roles where declared |

All retain original evidence ranges and explicit wall-clock/durable-receipt policies.
The bank-specific sender detector remains unchanged and only the router calls it.
Role and learned matches conflict, even with the same family. Disabled role vocabulary
still detects conflicting/unsafe context; it cannot authorize a financial result.
OTP, non-execution and quoted-message checks remain shared safety gates.

## Persistence and protection

Existing AlJazira teaching is preserved. Startup adds only missing built-in ids and
sender aliases; it never replaces a user revision or re-enables a disabled one.
Other banks claiming an AlJazira sender remain routing conflicts.

Role provenance uses the existing `engine:` and `rules:` metadata fields. The latter
contains the full configuration fingerprint plus selected id/revision. This preserves
the original financial fields, evidence and validation findings. The write guard
checks that stamp and current enabled definition inside the financial transaction.
Same-generation restores with changed content also invalidate it. At this milestone the
encrypted backup carried schema 3 in `preferences.json` and Room was v18; since v22 the
patterns travel inside `masroof.db`.

Successful or human-edited saved evidence is resumed, never reinterpreted. A correction
received before any ParsedEvent exists allows initial extraction while retaining the
human overlay. Reading installed definitions does not acquire an exclusive restore
lease; only an actual missing-default installation needs one.

## Verification and remaining release work

The unchanged frozen baselines still cover 74 parse inputs / 2,690 comparisons and
seven financial scenarios / 834 comparisons. The candidate now loads the shared bundle;
the reference retains the direct generic rules path. The codec test independently
compares every bundled literal, role, observation and network setting with the original
profile. No baseline or financial oracle was regenerated.

Final checks passed 116 engine tests, 149 targeted app tests and 18 API 36 device
tests, with both APKs built. Device coverage includes the shared AlJazira controls,
STC approval, nine family-learning journeys and three financial replay journeys.
An emulator System UI ANR obscured an earlier screenshot/focus run; after dismissing
that system dialog, all 18 passed together and both theme screenshots were reviewed.

Remaining release gates are a signed APK
upgrade with existing data, complete encrypted restore/rollback and process-interruption
rehearsals, and PR/main CI. Authoring richer card/statement roles from user examples and
accounts known only by partial references remain separate capability work. The proven
generic role matcher is intentionally retained; this milestone does not rewrite those
declarations as whole-message example templates.

At this milestone the financial database supported v18 only: older-schema migrations,
import hashes and automatic fact backfills were removed, and databases and backups older
than v18 are still rejected without erasing live data. Since then, schema changes above
v18 go through additive, tested migrations (see `docs/ARCHITECTURE.md`).
The v18 schema/hash, encryption and restore/rollback remain unchanged (the one-time
transfer-integrity repair was deleted in plan 3.3c; see `docs/ARCHITECTURE.md`). The retired backfill worker is deleted; startup cancels any queued request by its
persisted work name (`RetiredWorkCleanup`).

Removal verification passed 194 targeted app tests and 15 API 36 device tests,
with both APKs built. Coverage retains encrypted v18 verification/import, restore
interruption and rollback, startup blocking, live retries and both bank flows.
Older files and disguised older backups are rejected before live data is changed;
the unchanged v18 schema export remains pinned by its original SHA-256 test.

## Reference test ownership

Bundled JSON is the production definition source. The unchanged Kotlin profiles
are retained under `bank-engine/src/testFixtures/` for independent interpreter and
financial-reference tests. Ordinary app fixture helpers use the bundled pattern
adapter by default; reference comparisons opt into the Kotlin profile explicitly.
The injectable `InterpretationTestAdapter` is compiled only into app tests.
