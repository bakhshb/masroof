# M8.2 — Conservative ownership, matching and atomic posting contract

This document records the initial pure-contract increment. The subsequent Beta-only durable adapter,
schema 4→5 and recovery verification are described in [M8_DURABLE_LEDGER.md](M8_DURABLE_LEDGER.md).
The in-memory reference remains available and shares its financial preparation/replacement policy
with that adapter, including the documented currency-bound FX safety guard. Runtime adoption and
full end-to-end acceptance remain open.

This increment adds a pure Finance Core API under `lab/finance-core`. It does not adopt a new runtime,
create a Room ledger, change a schema, touch production data, or delete legacy code. The in-memory
adapter is an executable reference contract for a future durable Beta adapter. M8.2 remains in progress
until that adapter, its recovery tests and full legacy-vs-Beta parity are implemented.

## Confirmed ownership

`ConfirmedOwnershipResolver` accepts exact registry `AssetId` keys. Bank, environment and local ID
must agree with the stored asset; debit/credit cards require an exact same-bank, same-environment
account/facility parent of the right kind. Registry entries represent ownership previously confirmed
by the user. Discovery candidates, unlinked cards, missing registry entries and suffixes do not create
ownership. `Unconfirmed` never means an external counterparty.

## Conservative self-transfer matching

`SelfTransferMatcher` takes a complete candidate pool for the requested proof. It requires exactly
one outbound and one inbound observation, distinct keys and disjoint evidence, eligible financial
gates for both observations, equal native amount/currency, exact resolved source/destination, and
consistent FX/loan/income attribution. Conflicting registry entries stay in review.

Automatic matching requires a **bank-issued unique transfer reference** from validated structured
evidence, with an explicit issuer. Both owned account endpoints must belong to that issuer. The stored
instants must be at most ten minutes apart, inclusive; the matcher never reinterprets bank wall clocks.
Memos, merchants, numeric body fragments, amount/time similarity and suffixes are not proof. A shared
reference with more than two candidates is ambiguous, even if a convenient pair could be selected.

An explicit `UserPair` decision may pair cross-bank observations or a wider time gap. It binds the
decision ID to the selected keys, revisions and exact original evidence sets. It does **not** resolve
ownership, remove blockers, create parse success, or grant financial authority. Each leg still passes
the existing classifier/PostingGate. A comparison or acknowledgement from M7 is never authority.

The outgoing observation supplies the canonical transaction key, revision and financial time. Both
original inputs remain in the paired record with their separate original times, zones, references,
revisions, amounts and evidence. The matcher merges evidence and registry snapshots; it does not
rewrite either observation. A mismatch in core financial facts is review, not an inferred conversion.
The caller must coordinate a newer canonical posting revision when either observation changes. A
changed incoming observation at the same canonical revision conflicts instead of silently replacing
the stored provenance; per-message parser version counters are not a new transaction identity.

This deliberately conservative API is not an implementation-equivalence claim against the legacy
heuristic matcher. It is not adopted by reconciliation, live/history intake, dashboard or posting UI.
Broader matching and exact legacy ledger parity require separate evidence before runtime adoption.

## One atomic posting boundary

`FinancialPostingLedger.post` accepts a gated input or checked pair. It re-runs financial classification
inside the boundary and never accepts a bare `PostingCommand` or caller-constructed classification as
authority. The original `PostingStore` DTO/interface remains unchanged for compatibility.
Plain `SELF_TRANSFER` inputs are refused: two resolved but unmatched messages cannot each create a
ledger row. This increment supports checked pairs only; an authoritative single-message transfer
proof remains a separate future contract rather than an unguarded alternative entry point.

The reference adapter serializes operations and stages a complete immutable state before publishing:

- current financial result and its exact revision/payload;
- append-only revision history and original paired-observation provenance;
- evidence claims scoped by environment;
- bank-reference/user-pair proof claims scoped by environment and issuer.

An identical complete payload/revision is idempotent. Equal revisions with different facts, links,
eligibility provenance, metrics or pairing provenance conflict. A lower revision is stale. One message
cannot fund two transactions; one pairing reference/decision cannot fund two keys. Old claims remain
reserved even when a newer revision reduces its current evidence set or no longer has a pairing proof.
Similar but independently evidenced messages remain independent transactions.

A later interpretation cannot silently remove or rewrite a prior confirmed financial decision at the
same correction revision. A changed decision needs a newer correction revision; the full decision
overlay/editing workflow is still M8.3. No raw SMS or stored manual decision is edited by this API.
The guard includes effective type, financing/income attribution and FX, so a confirmed fee cannot
silently become a loan repayment. An existing complete FX pair cannot disappear or be replaced by a
later interpretation/market refresh; replacement requires a newer explicitly confirmed financial
correction revision. No rate is computed or rounded by this API.

All caller-owned collections are snapshotted before validation. Exposed records/history use immutable
collections, so a retained mutable collection or a cast of a returned view cannot modify previously
validated amounts, registry, impact or evidence.

The test-only pre-publication fault seam demonstrates that a thrown write leaves no partial revision,
result, history or claim and can retry safely. A durable adapter must enforce the same boundary in one
database transaction; a process-local lock or this reference store is not durable recovery evidence.

## Verification and remaining acceptance

Run `:lab:finance-core:test :lab:finance-core:verifyPureJvmDependencies` and existing full CI.
Tests cover exact ownership and typed parents, suffix collisions, bounded explicit-reference matching,
many-to-one ambiguity, independent similar messages, per-leg authority gates, confirmed decision
bindings, amount/currency/time/FX/provenance preservation, retry/conflict/stale revisions, retained
claims/history/manual decisions, rollback, caller/read snapshot isolation, environment separation,
and real multi-threaded same-command/same-evidence races.

Local verification on 2026-10-16: **71 tests passed**, zero failures/errors/skips, including 27 new
matching/posting regressions and all 44 existing finance tests. The pure JVM boundary guard passed.
Full PR CI and device checks remain required before merge.

No reference parser, corpus, golden fixtures, existing DTOs, classification definitions, dashboard
definitions or schemas are modified. Actual Beta ledger storage, user correction overlays, complete
old-vs-new financial parity, process-death durable recovery and runtime adoption remain future work.
