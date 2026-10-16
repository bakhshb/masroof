# M8.2 — Durable Beta posting ledger

This increment implements `FinancialPostingLedger` in the independent Beta Room database. It does
not adopt financial posting in intake, historical import, M7 comparison, reconciliation or dashboard.
Those paths still cannot turn a comparison acknowledgement into financial authority. Production
storage and legacy code remain unchanged.

## Schema and atomic boundary

Beta alone advances **4 → 5** by additive Room auto-migration. Historical schemas 1–4 are retained.
No existing evidence, interpretation, decision, registry or bank-selection row is backfilled/reparsed.
The four new tables start empty:

- `beta_ledger_current`: exact latest financial snapshot and revision per environment/transaction ID;
- `beta_ledger_history`: append-only snapshots keyed by the complete correction/interpretation revision;
- `beta_ledger_evidence_claim`: unique environment/evidence ownership, retained across corrections;
- `beta_ledger_proof_claim`: unique environment/kind/issuer/reference ownership, also retained.

Issuer is non-null; a user pairing decision uses the empty issuer sentinel and its distinct
`USER_PAIR` kind. SQLite nullable uniqueness cannot permit a second user-proof claimant.

`RoomBetaFinancialLedger` prepares an eligible input/checked pair through the same pure policy as the
reference adapter. Mechanical extraction into `FinancialPostingRules` shares preparation and the
existing manual-decision, immutable-FX and paired-provenance replacement guards. Existing public
input, posting, revision and ledger contracts are unchanged. One additional Beta safety guard rejects
reusing the identical stored FX pair after changing native currency, even at a newer manual revision:
that pair's provenance belongs to its original native currency. Same-currency amount corrections may
retain it; a new explicit correction may supply a newly resolved whole pair for a new currency. Pure
and Room regression cases cover both. No monetary calculation definition changes. No bare command/classification enters
the adapter as authority.

Inside one Room transaction, the adapter reads only the selected current row and indexed claims,
checks revision/full payload, verifies supplied ownership snapshots against the actual Beta registry,
claims any new evidence/proof, replaces current and appends history. Exact retries do not write;
equal-revision changes conflict; stale revisions return the existing snapshot. Previously consumed
evidence and proofs remain reserved when later explicit corrections remove them from the current
record. Unregistered caller-built ownership is refused. Existing snapshots are historical and are
not rewritten when a registry entry changes.

Financial reads load stored results, never rerun a classifier/parser. Writes do not scan/replay other
transactions or the ledger's historical revisions. `history(key)` is an explicit per-transaction audit
read, not an intake dependency. Database transactions and composite keys arbitrate independent adapter
and database instances; a process-local mutex is not the durability mechanism.

## Encoding and lifecycle

The explicit `BETA_LEDGER_1` binary snapshot stores full gated input, exact original registry and paired
observations, command, metrics/account movements, revision/time/zone, correction authority, evidence
and match identity. Decimal rates retain full precision and scale/source. Immutable decoded collections
prevent mutations of earlier records. The codec rejects unknown versions, invalid lengths, duplicate
keys, invalid UTF-8, inconsistent command/input, trailing data and checksum mismatch. The SHA-256
checksum detects accidental damage; it is not an authenticated backup format or a financial signature.
No raw SMS body or parser archive is included.

Explicit `BetaContainer.postFinancial`/read/history methods use the existing backup/reset lifetime lock
and recreate the adapter against the current database after reopening. Existing Beta backup includes
all four tables. Restoring a version-4 Beta backup upgrades to version 5 and invents no financial rows.
Reset clears Beta only. Production database paths and schemas are unchanged. Beta retains its existing
backup file and preference namespace; no automatic posting entry point is introduced.

Backup verification here covers valid Beta backups. The existing offline restore copies over the
live Beta database before opening/validating it; it does not stage/authenticate corrupt or interrupted
backups atomically. The container lifetime lock is per container, not a cross-container maintenance
lock. This increment does not claim M11 restore hardening or safe concurrent maintenance from
multiple container instances. Corrupt ledger snapshots fail closed when read/written; that is separate
from failure-atomic backup replacement.

## Verification

The existing Finance Core tests protect the reference contract during extraction. New Room tests
compare outcomes with that reference; check revisions, manual decisions, FX precision, typed card and
loan facts, negative metrics, original paired observations, retained claims, immutable reads, corrupted
payload failure, backup/restore and reset. Fault injection after all writes proves rollback for initial
posting and a later correction. Independent adapters compete for the same evidence; identical
parallel retries append one revision.

Device tests exercise independent Room database instances competing on actual Android SQLite,
1→5, 4→5 preservation of every prior data family, and restoring a v4 Beta backup. The two-database
contention case runs on device because Robolectric's legacy sqlite4java backend reports a shadow
connection `SQLiteBusyException` for that scenario; its one-database adapter races remain JVM tests.
Two externally controlled force-stop journeys run only in the existing lifecycle job: an actual process
is killed after all writes but before COMMIT, then recovery retries exactly once; a separate committed
posting is killed and reopens with exact manual/FX results, one history revision, duplicate prevention,
and backup restoration. The process ID must differ. These phases are excluded from emulator-smoke.
No new emulator, Android matrix or repeated full test suite is added.

Final verification evidence belongs in this PR's validation section once checks finish. This does not
close full M8 acceptance: end-to-end M7/M8 adoption, persistent correction overlays, comprehensive
financial equivalence and independent-corpus/performance acceptance remain separate work.
