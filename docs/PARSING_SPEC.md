# Bank interpretation

The production adapter is `app/.../bank/MessagePatternSmsAdapter.kt`; `application/interpretation/ConfiguredPatternAdapters` supplies immutable bank-scoped snapshots from the pattern store. Interpretation runs in `bank-engine/`. Routing evaluates all adapters before parsing. Every adapter detects its bank with `SenderBankDetector`, using `SenderIdentity` (bank-engine) as the one rule for when two sender names are the same sender.

Bundled JSON assets in `app/src/main/assets/bank-patterns/` define the production bank patterns. `PatternFactInterpreter` extracts facts with evidence and blockers, delegating AlJazira role profiles to the shared `RuleInterpreter`. Kotlin `RuleLibrary` and `AlJaziraRuleProfile` are interpreter/reference test vocabulary; production does not load them. Local family assistance is injected from `ml-assist`; rules and security checks remain authoritative. `InterpretationDraftMapper` preserves blockers and sends drafts through the existing validator and `ParseFinalizer`. Only that finalizer emits successful financial parse results.

The old message parser, parsing pipeline, classification resolver, heuristics and extraction helpers are removed from runtime and tests. Tests replay canonical fixtures and financial ledger scenarios through the new engine. Bank-qualified references do not infer ownership. Missing, contradictory or unexecuted facts remain reviewable.

The financial schema and transaction assembler remain unchanged. A parse result is not a posted transaction. See [AGENTS.md](../AGENTS.md) for gating, timezone, completion and retry requirements.

See [architecture ownership](ARCHITECTURE.md#message-pattern-ownership) and [pattern facts and write gate](PATTERN_FACT_CONTRACT.md).
