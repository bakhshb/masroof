# Pattern facts and financial write gate

Configured banks now use the existing live and historical processing paths.
Teaching and the STC bundle still create preview-only patterns: financial use needs
separate explicit approval. AlJazira now uses the same configured adapter and store,
with preapproved role definitions preserving its existing behavior.

## Definition and conversion

`SavedMessagePattern.factContract` is optional. A matching pattern without it
returns `REVIEW_REQUIRED`, an unknown family and no financial facts. Display labels
are never converted to financial enums. Approval asks for the financial movement
independently from the label, a confirmed account and a matching example.

`PatternFactContract` v1 supplies a family, direction, optional transaction currency
and explicit account/card bank identities. It contains no ownership, financial
transaction type, container id or posting flag.

`PatternFactInterpreter` freezes the configuration and evaluates every enabled
current definition for the routed bank. Overlaps between approved patterns remain
conflicts. Preview-only patterns appear in previews but never compete. Sender
detection is never repeated after routing.

Supported roles: `amount`, `merchant`, `counterparty`, `biller`, `biller_code`,
`reference`, `occurred_at`, `source_account`, `destination_account`, `card`,
`available_balance`, `outstanding_balance`, `international_fee`, `foreign_amount`.
Unknown roles block use; explicitly ignored slots remain ignored.

- Money needs a currency in its own span, or an explicit transaction currency for
  bare `amount`. Transaction currency never fills balance/fee/foreign currencies.
  Contradictory transaction currency blocks use. Production validation still checks
  positivity, family/direction compatibility and plausible timestamps.
- Full timestamps stay local for the existing financial time policy. Date-only
  fields are incomplete; midnight is never invented. Missing time uses only a
  durable SMS receipt instant.
- Account/card identities require four normalized digits and an explicit bank.
  No padding, truncation, name-to-bank lookup or ownership inference occurs.
- Unresolved reference fields retain their original evidence and block automatic
  use. Contract v2 adds exact bank/role-qualified links to an existing four-digit
  account. A different observed reference does not use that link. No padding or
  automatic suffix matching resolves a partial key. Context-only reference fields
  need an explicit choice and never become account/card identities.
- Shared OTP evidence wins. Existing non-execution and notice-context safeguards
  also block pattern financial use.

Every mapped value has original UTF-16 evidence offsets, a definition source
(family, direction, currency, reference bank) or a durable receipt source.
`InterpretationDraftMapper` preserves hash, pattern id/revision and source metadata
in existing confidence reasons via `parsing/model/PatternInterpretationStamp` and always uses `ParseFinalizer` and the production
validator. No financial schema change is needed.

## Persistence and write checks

Configuration schema 1 stays readable and preview-only. Schema 2 is emitted only
when a contract exists; its closed fields reject unsupported contract versions
before save/restore. Changing a contract requires a new immutable pattern revision.
The existing encrypted backup/restore path carries the same document.
Contract v2 persists account bindings and context-reference choices in this same
document. The encrypted export test now includes an exact partial-reference link.
Configuration schema 3 adds immutable role-profile definitions and retains schema
1/2 readers. It reuses the strict literal-role codec and generic extraction grammar;
the role rule must agree with the pattern's explicit fact contract, apart from approved
account/card links (`PatternAccountRole.CARD` names a card link; links never carry a reference field).

The deterministic SHA-256 covers generation, bank identities/senders, definitions,
enable states, revisions, contracts and history with length-prefixed encoding.
Reordering definitions preserves it; same-generation restores with changed content
do not. Automatic pattern facts require this hash and an enabled current id/revision
with a contract inside the financial Room transaction. Missing, corrupt, unsupported
or stale configuration refuses the mutation. The existing access lease prevents
edits/restores between the check and the write.

Explicit automation-confirming corrections and `USER_FINANCIAL_TYPE` retain manual
authority, while comparison against current effective evidence still rejects older
assemblies after an edit. Merchant/counterparty-only edits do not bypass the pattern
check. Existing protection of already linked manual decisions remains in force.

## Activation and processing

- STC defaults are installed before the container admits work. Opening Settings is
  not required for intake. User banks, overlapping senders and disabled revisions
  are preserved. AlJazira defaults are merged by missing id, retaining teaching and
  disabled revisions. Dynamic routing evaluates every adapter and retains conflicts.
- Explicit account confirmation uses the existing ownership service. Approval
  validates the complete proposed configuration through the production finalizer,
  checks the full prior fingerprint and current ownership under exclusive access,
  then creates a revision. Save does not reparse or resolve a review. Back discards
  the approval draft; review-source text remains read-only.
- Approval uses one page with up to ten uniquely matching, unprotected imported
  examples from the existing bounded pending scope. The user explicitly chooses
  whether SMS references identify their own account or only the counterparty;
  the latter binds the selected confirmed account as a pattern constant. Inline
  validation is read-only. Save makes approval visible and exposes separate,
  pattern-scoped processing with bank review counts before and after. Revision
  history is under advanced options and is not an approval indicator.
- Protection and parse/store share a Room transaction. Posted evidence, corrections
  and resolved decisions are never re-parsed. Retries resume derived processing
  using saved events and effective human overlays. Captured adapter snapshots that
  expire before processing fail before saving facts, allowing a fresh-route retry.
- Explicit pending application reuses the historical batch: parse/store per eligible
  row, then one discovery/reconciliation/review pass. Eligibility is rechecked in
  the transaction. Posted, corrected, resolved and already-successful events are
  excluded. Bank/pattern conflicts stay in review. The current limit is the first
  1,000 required review rows; up to 50 results are displayed; eligible rows in the bounded preview are considered per
  application, with a partial-scope notice. Successful unposted evidence with a revoked definition needs explicit
  manual review rather than an automatic reparse.
- Import uses the existing scanner and permission flow with bank scope. Scope never
  forces a bank or certifies whole-inbox coverage. Existing rows are deduplicated.
- Reconciliation failure retains the atomic HISTORICAL_BATCH retry set. Recovery
  clears it only after writes and review updates succeed. Parse failures keep the
  existing per-message recovery. Known failure counts are shown; a thrown failure
  without a returned report leaves the count explicitly unavailable.
  Pending reprocessing uses the existing `saveForHistoricalBatch`: parsed facts
  and retry intent are committed atomically before the batch-derived pass. A
  regression stops before `finish` and verifies that recovery posts once and
  clears the marker; this is not a device process-death rehearsal.
- Automatic bound-account writes recheck the bank, role, canonical reference and
  current OWNED status in the financial guard. Classification stays in the existing
  core: a confirmed owned transfer leg can become an external transfer. Internal
  wording does not establish self-ownership; incoming amounts are not income merely
  because of their pattern label.
- The user-provided real STC OTP is stored with its code removed. It mentions a
  transfer and amount but stays OTP/non-financial in the adapter contract and actual
  ingestion pipeline.

## Remaining scope

AlJazira's shared-definition cutover and frozen parity checks are complete. Its
generic role matcher remains a supported matching form alongside taught slots.
Approval requires a genuine four-digit canonical account; accounts known only by
partial identifiers need an explicit identity design across core and consumers.
Each approved template currently selects one account per role. Wider alias and
card/statement-role authoring still needs coverage. Signed APK upgrade, process-death
recovery, complete restore/rollback rehearsal and PR/main CI remain release gates.
No user phone has been changed.

The protection transaction covers evidence checks and parse/store only. Live
derived processing and durability probes run after commit, so process death at a
reported durable stage cannot roll back the preceding evidence or ledger write.
