# Project Overview & LLM Guide

`scala-purrism` is a Scala 3 tooling repository dedicated to automated refactoring, linting, and style enforcement for Typelevel Scala code, focusing on Cats and Cats Effect.

The core `scalafix` module houses Scalafix semantic rules that automatically migrate and rewrite code toward pure, polymorphic Typelevel standards.

## Key Highlights

- **Golden Fixtures as Source of Truth**: All refactoring rules are specified by input/expected code pairs in `scalafix/test/resources/golden/typelevel/base` and `.../expected`. Fixtures are authoritative—any rule addition or modification requires matching fixture updates.
- **Polymorphic Effect Refactoring**: Transformations move concrete effect usage (e.g. `IO`) to abstract higher-kinded type parameters `F[_]` with Cats typeclass constraints (`MonadThrow`, `Sync`, `Temporal`, etc.), reserving concrete `IO` for application entry points (`IOApp`).
- **Semantic Safety & Idempotence**: Rules leverage Scalafix semantic inspection (`SemanticDocument`, symbol lookup, type inference). Rewrites must be safe, deterministic, and idempotent. Ambiguous cases produce diagnostic warnings rather than partial edits.
- **Extensible Architecture**: Built with Mill under Scala 3.8.4. Additional modules beyond Scalafix can be introduced under the same repository structure as the toolkit evolves.

## LLM Clarifications & Guidance

1. **Tooling & Inspection**: When analyzing Scala code structure, symbols, types, or implicits, prefer **ScalaSemantic MCP tools** over raw shell text tools (`cat`, `rg`, `sed`).
2. **Command Execution**: Always prefix shell commands with `rtk` (e.g. `rtk mill scalafix.compile`).
3. **Documentation Hierarchy**: Shared project rules live in `docs/` ([RULES.md](docs/RULES.md), [GOLDEN_FIXTURES.md](docs/GOLDEN_FIXTURES.md)). Root and tool-specific LLM files (`AGENTS.md`, `GEMINI.md`, `CLAUDE.md`, `.cursorrules`, `.clinerules`, etc.) serve as lightweight pointers to these shared docs.

## Build & Test Commands

- **Compile rules**: `rtk mill scalafix.compile`
- **Run tests**: `rtk mill scalafix.test`
- **Render documentation**: `rtk mill docs.run`

## Testing on live code
Here list of projects can be used for testing: [Local Environment Test Repositories.md](Local Environment Test Repositories.md)
