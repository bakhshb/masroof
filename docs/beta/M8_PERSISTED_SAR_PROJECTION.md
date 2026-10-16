# M8.4 continuation: persisted-fact SAR projection

Status: In Progress. Beta-only, pure JVM and read-only. No Room migration, runtime adoption,
production app change, rate network request, raw SMS access, original source/hash/golden change,
or legacy deletion. The parallel durable-ledger path owns Beta schema 4 to 5.

## Scope and source policy

`PersistedSarProjection` consumes the existing resolved `FinancialClassificationInput`. It runs
the existing classifier gate first and preserves the native amount, currency, full-precision
`AppliedFx` rate/source, ownership, evidence and revision. SAR metrics are a separate derived
projection, never replacement posting facts or a new posting authority.

Policy comes from the actual original `TransactionSarEquivalentResolver` persisted-rate branch:

- A complete stored rate/source pair is used as stored. Sources are exactly `SMS`,
  `HISTORICAL_MERCHANT`, `MARKET` from the existing original enum. Unknown strings are unavailable.
- Foreign magnitude is multiplied by the full rate, rounded to two decimals with HALF_EVEN,
  then a SAR international fee is added. There is no early rate rounding.
- A linked international fee applies only to non-refunds with source `SMS`. Refunds always
  exclude it; historical/market resolutions do not inherit an SMS fee. Native SAR bypasses FX
  and foreign fees, matching the original resolver's native-currency skip.
- Fee facts carry the stable posting key, exact revision and one of its linked evidence IDs.
  A mismatched link/revision or non-SAR applicable fee makes the row unavailable. The adapter
  supplying this read contract must select the persisted linked event's fee; the DTO itself does
  not prove parser validity, ownership or event selection, and cannot post anything.
- No persisted pair means explicit `MISSING_APPLIED_FX`. The original linked-event, dated merchant
  and market enrichment fallbacks are deliberately not implemented or claimed equivalent here.

`SarPeriodProjector` accepts one current row per stable key in one explicit environment. Shared
evidence across different keys, duplicate revisions or ambiguous fee inputs are rejected. The
period is `[from, until)` over the stored instant; callers choose the period/zone. Each row is
converted before aggregation, retaining per-row rounding. If any in-period row is unavailable,
all complete totals are null and its reason is exposed. Missing is never zero or a partial total
presented as complete. A truly empty period has known zero totals.

Primary and supplementary card purchase/refund impacts aggregate by confirmed facility ID.
Credit-card settlements remain account outflows and never become a second credit purchase.
Loan payments include explicitly attributed financing fees and remain separate from spending.
These are period activity totals, not statement dues, facility outstanding or loan remaining debt.

## Executable evidence and limits

The existing hash-pinned actual original converter and monthly calculator remain unchanged.
The four existing USD/EUR/GBP cases now assert actual converter SAR results against the candidate
projection, while separately asserting unchanged native facts. The previous native-only blocker
in `M8_FINANCIAL_PARITY.md` describes the earlier PR and is superseded for this narrow persisted
rate projection only.

A new cross-run doubles the 20 resolved cases into 40 mixed SAR/USD cases and compares all eleven
monthly metrics per case, whole batch, empty batch and refund-only negative aggregate against
the actual original monthly calculator using actual original converter equivalents. It includes
fees, refunds, loans, financing-attributed fees, bills, purchases, internal/external transfers,
credit purchases, supplementary cards and credit settlements. Sixteen new projection tests cover
source/fee rules, HALF_EVEN, precision, zero/negative values, gate failures, missing FX, evidence
and revision binding, half-open periods, unavailable completeness, environment isolation,
duplicate prevention and unmodifiable output maps.

The original full resolver is inspected as policy authority but is not compiled/cross-run here:
its source contains the actual HTTP provider dependency. There is no substitute resolver stub or
second claimed oracle. The actual converter and monthly calculator are cross-run; additional
credit-facility and loan period assertions are authored regression checks, not independently
certified full dashboard parity.

Remaining M8.4 gates: actual enrichment fallback/date/source-selection parity, durable-ledger and
correction integration, full account/card/facility/loan dashboard projections including dues and
balances, and independent holdout/performance acceptance. This PR does not mark M8.4 complete.

Run `:lab:finance-core:test :lab:finance-core:verifyPureJvmDependencies`; the existing safe report
`build/reports/m8-parity/summary.txt` is already included in the existing Lab CI artifact. No new
job, emulator, matrix or duplicate suite is introduced. Final-head CI evidence belongs in the PR.
