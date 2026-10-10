# Lifecycle audit fixes

Baseline: main `772de0c21f7ca1b1ea96cbc1fe3e787b642c6ab3`. Traces and implementation decisions recorded before changing production code.

| Finding | Entry through failure and downstream effect | Root boundary and regression |
| --- | --- | --- |
| F01 | Historical scan captures RawSms, stores ParsedEvent without derived work, then finishes one batch. Process death before finish loses the in-memory set; startup excludes parsed evidence without retry markers. | Save historical ParsedEvent and HISTORICAL_BATCH intent in one Room transaction. Clear only after complete derived work. Device arm/resume after historical parse with maintenance already current. |
| F02 | Details ignore deletes an exclusive financial movement, then persists USER_NON_FINANCIAL separately; an interruption loses the decision and replay may recreate the movement. | One Room transaction for the decision, deletion and retry cleanup; fault injection must roll everything back. |
| F03 | Scanner discards finish's Incomplete; UI conversion prioritizes parsed over failed; rescan and manual reparse erase failedCount. | Preserve incomplete results through gateways and ViewModels; never claim full success for failed work. |
| F04 | ZIP extraction writes plaintext, then failure directly deletes a partial file before outer cleanup can wipe it. | Wipe at the extraction discard boundary; test bytes at deletion for limits, CRC and source failures. |
| F05 | Incoming DB is installed, preference commits are ignored, then COMMITTED removes rollback evidence and restarts. | Check every preference commit before COMMITTED; inject each commit failure and verify original DB/preferences recovery. |
| F06 | Token text field uses rememberSaveable before encrypted preference storage. Activity saving duplicates the secret into Bundle. | Non-saveable token state; restoration test excludes the typed secret. |
| F07 | onResume reloads onboarding preferences during an active retained import job, resets IMPORTING to IMPORT_DATE/Idle and blocks the apparent retry. | Preserve active in-process work; a genuinely new process still restores the retryable date step. |
| F08 | ViewModel Main scope calls historical scanner; lazy provider query, cursor iteration and parsing inherit Main. | Inject a dispatcher at the scanner boundary; deterministic context test, no sleeps. |
| F09 | Initial startup deferred completes BLOCKED; successful retry changes only the first UI's state. A recreated Activity reads the stale initial outcome. | Process-local current startup authority updated by retries, while new processes still start pending. |
| F10 | Fixture category assertions compare platform-specific relative paths with slash literals. | Normalize the path representation and cover both separator forms. |
| R01 | Import closes Room and renames database files while repository operations, workers and live intake can still enter. Existing references can reopen the old process's helper or access a changing database. | A shared access boundary admits concurrent normal operations, drains them before exclusive restore, and retires every old-process repository before close. Device tests use barriers around OLD_PARKED and prove ungated exposure plus gated exclusion. Post-close failure recovers original files and restarts instead of reusing closed Room. |

No schema, parser, matcher, financial calculation, retry-mode or restore-journal-stage changes. Historical processing stays one derived batch. Live WorkManager input stays rawSmsId only. UI state never becomes startup authority or secret storage. Release workflow remains unchanged; regression/device checks run in CI.
