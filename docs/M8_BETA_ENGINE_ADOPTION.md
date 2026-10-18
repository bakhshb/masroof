# M8 — Current bank-engine financial adoption

Source: ADR-19 / Notion page 12. Base: `a9e995a5` after M7 PR #294. This slice does not delete the old parser.

## Authority

`BetaFinancialArchive` is the one financial reading of a saved outcome.

- A current row keeps `baselineJson` as `{}` and stores the bank-engine archive in `candidateJson`. Explicit approval reads that archive: current engine and rule versions, original evidence spans, `SUCCESS`, no blockers, an executed state, and no security, OTP, or conflict diagnostic.
- Amount, currency, and family use the latest human label only when its provenance, archive hash, attempt, and engine versions match that archive and the selected original text still matches the SMS. A model suggestion or a label from an older interpretation does not change the amount.
- A new label changes the approval fingerprint, so a draft opened before the correction cannot post the old amount.
- A historical baseline remains an explicit archive. A current candidate stored beside it does not replace the posted amount. Existing ledger rows are not rewritten on read.
- OTP, unexecuted, empty-span, and non-current archives stay unposted.

Dashboard financial facts and auxiliary facts use the same reading, so a posted Mada purchase can link its debit card and a posted financing installment can keep its loan type.

## Not in this slice

No Room migration. No production database access. `lab/engine`, `LegacyInterpretationAdapter`, and `SharedRuleInterpretationAdapter` remain for historical rows and regression tooling. ML training and model promotion stay off. Deleting the copied parser waits until nothing in the Beta runtime still needs it.

## Verification

`./gradlew :lab:beta:testDebugUnitTest` — 267 tests, 0 failures. The new cases cover Mada purchase, credit-card payment, refund, bill, loan installment, inter-bank transfer, SMS exchange rate, human amount correction, rejected model and stale labels, OTP, unexecuted messages, historical baseline preservation, and recovery of a committed approval after the process-death retry row is restored. The emulator force-stop harness is unchanged; this recovery uses the existing reopen contract.
