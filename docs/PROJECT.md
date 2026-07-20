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
3. **Documentation Hierarchy**: Shared project rules live in `docs/` ([PROJECT.md](PROJECT.md), [RULES.md](RULES.md), [GOLDEN_FIXTURES.md](GOLDEN_FIXTURES.md)). Root and tool-specific LLM files (`AGENTS.md`, `GEMINI.md`, `CLAUDE.md`, `.cursorrules`, `.clinerules`, etc.) serve as lightweight pointers to these shared docs.

## Build & Test Commands

- **Compile rules**: `rtk mill scalafix.compile`
- **Run tests**: `rtk mill scalafix.test`
- **Compile documentation**: `rtk mill docs.compile`

## Local Environment Test Repositories (Proposals)

The following local repositories serve as prime candidates for testing `scalafix-purrism` rules in real-world Typelevel Scala codebases:

1. **`gh-tasks-llm-executor`**
   - Path: `/Users/viktorskalinins/IdeaProjects/my/gh-tasks-llm-executor`
   - Build Tool: Scala-CLI / Mill (Scala 3.8.4)
   - Stack: Cats Core `2.13.0`, Cats Effect `3.7.0`, `munit-cats-effect`, `arrowstep`
   - Notes: Active codebase already importing `scala-purrism-scalafix_3` via `project.scala`.

2. **`arrowstep`**
   - Path: `/Users/viktorskalinins/IdeaProjects/my/arrowstep`
   - Build Tool: Mill (Scala 3.8.4)
   - Stack: Cats Core `2.13.0`, Cats Effect `3.7.0`, `os-lib`, `ujson`
   - Notes: Pure Cats/Cats Effect library with `-Xsemanticdb` enabled in `build.mill`.

3. **`cctv-analyzer-scala`**
   - Path: `/Users/viktorskalinins/IdeaProjects/my/cctv-analyzer-scala`
   - Build Tool: SBT (Scala 3.8.3)
   - Stack: Cats Effect `3.7.0`, FS2 `3.13.0`, Http4s `0.23.34`, Circe `0.14.15`
   - Notes: Full Typelevel web application with `semanticdbEnabled := true` and `scalafixDependencies` configured.

4. **`ScalaSemanticMCP`**
   - Path: `/Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP`
   - Build Tool: Mill (Scala 3.8.4)
   - Stack: `mill-scalafix` (`0.6.0`), Scalameta `4.17.0`
   - Notes: Mill build using `mill-scalafix`, ideal for verifying rule integration on Mill builds.


