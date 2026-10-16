# M8.3 — Evidence-bound correction overlays

This increment implements executable, pure decision submission and interpretation rebase in
`lab/finance-core/corrections`. It changes neither parser observations nor raw SMS. It has no Android,
Room, production runtime or production-data access. Existing classification DTOs and PostingGate are
unchanged. No migration, legacy deletion, parser/reference/golden edit or UI adoption is included.

## Authority matrix

| Explicit command | Effective authority | Financial behavior |
| --- | --- | --- |
| Keep plus merchant/counterparty/note metadata | METADATA_ONLY if no earlier financial decision | Cannot satisfy an unsuccessful parse gate; retains earlier manual authority if present |
| ConfirmFamilyOrAmount with type and/or native Money | CONFIRMED_FAMILY_OR_AMOUNT | May satisfy the existing parse gate; never removes unresolved blockers |
| ResolveFinancialType | USER_FINANCIAL_TYPE | May satisfy the existing parse gate; ownership, ambiguity and validation still apply |
| SuppressAsNonFinancial | No financial output | Persists suppression across reparse and metadata edits |

The trusted adapter supplies an uncorrected classification observation (`authority=NONE`, correction
revision zero) plus an explicit ordinary/security/unexecuted/informational disposition. Nonordinary
dispositions impose a sticky validation hold. No command clears that hold or emits parser success.
In particular, an explicit type is not a way to turn an OTP or an unexecuted purchase into a posted
transaction. UNKNOWN and unsupported ADJUSTMENT remain reviewable through the unchanged classifier.

An explicit amount/type confirmation has a positive executable path when the only obstacle was the
unsuccessful parse eligibility flag and the resolved input has no outstanding blockers. All existing
`ReviewReason` values survive both authorities, including generic VALIDATION. The coarse existing
reason cannot prove that a particular validator finding was resolved; typed finding resolution is
deferred rather than guessing that every validation error was an amount error. Security/finalizer
decisions belong to the interpretation adapter, never to editable metadata.

`FinancialDecision.ConfirmFamilyOrAmount.type` is the already resolved financial type selected by
the caller's family workflow. This package does not accept a bank message-family enum, infer its
financial mapping or rewrite the stored bank family.
SELF_TRANSFER always remains AMBIGUOUS_MATCH in this workflow: a type selection is not the checked
pair proof required by M8.2. Neither correction authority can substitute for that proof.

## Submission, priority and rebase

A request binds decision ID, stable environment-qualified posting key, exact displayed interpretation
revision/evidence and the expected preceding correction revision. A stale display cannot overwrite
another decision. Identical complete requests at the same correction revision are idempotent;
different requests conflict. Lower correction or interpretation revisions are stale. A decision ID
cannot be reused for a different correction. A changed payload at the same interpretation conflicts,
including changes to its safety disposition, registry or validation provenance.

The workflow copies caller-owned collections into unmodifiable snapshots. Its privately constructed
overlay retains the original input, latest observation, append-only decision history and metadata.
Evidence may grow on a later interpretation, but none of the evidence seen by the overlay may be
dropped. Key, environment and original evidence cannot be replaced by a similar message.

An explicit financial decision pins the selected type, native amount/currency, original stored
instant/zone, endpoints, financing/income attribution and complete full-precision FX pair. Reparse
cannot silently replace those facts with a different amount, ownership, time or market rate.
Metadata-only follow-up preserves this financial selection and its authority. A newer explicit
command changes only its requested amount/type fields; it does not accept other newly parsed facts
as an implicit financial correction. Current registry entries are rechecked; changes to any selected
owned endpoint or its parent impose OWNERSHIP review rather than moving the financial result to a
different account. No suffix matching or external-counterparty inference is available.
A currency-changing amount correction is rejected when the selected facts contain a complete FX
pair: a USD rate must not be reused for EUR or SAR. The workflow neither silently erases the stored
rate nor invents replacement FX authority. A currency change without a rate remains native-only;
downstream conversion still needs an explicitly resolved rate. Same-currency amount correction
preserves the complete full-precision FX pair.

Suppression retains earlier financial selection for audit and later explicit restoration, but
returns neither classification nor effective financial input. Metadata cannot restore it. A newer
explicit financial confirmation/type command may restore it subject to all existing blockers and
the safety hold. This pure result **does not remove a previously posted row**; a future transaction
coordinator must persist the decision and reverse/suppress any ledger impact atomically.
For nonordinary safety observations, even suppression returns FINANCIAL_REVIEW with VALIDATION
rather than hiding the safety review. It still exposes no effective financial input.

For metadata with no prior financial decision, a later interpretation supplies the newly observed
financial facts. It still passes PostingGate with only METADATA_ONLY authority, so eligibility must
come from a genuinely validated interpretation, not the metadata operation.

## Verification and boundaries

Run `:lab:finance-core:test :lab:finance-core:verifyPureJvmDependencies` and full existing CI.
Focused tests exercise positive amount/type authority, every unresolved blocker, all nonordinary
safety dispositions, sticky security hold, revision and decision conflicts, optimistic concurrency,
identity/environment/evidence binding, cumulative provenance, manual-priority rebase, exact native
money/FX/time retention, suppression/restoration, changed ownership and collection isolation.
Local verification on 2026-10-17: **104 finance tests passed**, zero failures/errors/skips, including
27 new correction regressions and 77 existing tests. The pure JVM dependency guard passed. Full PR
CI and device checks remain required before merge.

This is the pure M8.3 decision contract, not full M8.3 runtime acceptance. Durable decision storage,
atomic decision-plus-ledger changes, process-death recovery of those decisions, Review UI, typed
validator-finding resolution, explicit time/endpoint/FX correction commands and full old-vs-new
correction-ledger parity remain future work. The independent Beta ledger migration belongs to M8.2;
this PR owns no database schema and does not consume or mutate that adapter.
