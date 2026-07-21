# scala-purrism

Scalafix rules for refactoring Typelevel Scala code toward pure, polymorphic Cats
and Cats Effect style.

## Quickstart

Latest release:

```text
io.github.mercurievv:scala-purrism-scalafix_3:0.5.0
```

The published rule artifact currently targets Scala 3 and Scalafix `0.14.7`.

### sbt

Add sbt-scalafix to `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")
```

Add the rule dependency to `build.sbt`:

```scala
ThisBuild / scalafixDependencies +=
  "io.github.mercurievv" %% "scala-purrism-scalafix" % "0.5.0"

ThisBuild / scalacOptions += "-Ysemanticdb"
```

Run:

```bash
sbt scalafix
```

### Mill

Add Mill Scalafix support to `build.mill`:

```scala
//| mvnDeps:
//| - com.goyeau::mill-scalafix::0.6.0

import com.goyeau.mill.scalafix.ScalafixModule
import mill.*, scalalib.*

object app extends ScalaModule, ScalafixModule {
  def scalaVersion = "3.8.4"

  def scalacOptions = Seq("-Ysemanticdb")

  def scalafixIvyDeps = Seq(
    mvn"io.github.mercurievv::scala-purrism-scalafix:0.5.0"
  )
}
```

Run:

```bash
./mill app.fix
```

### Scala CLI

Add the external rule dependency as a Scala CLI directive:

```scala
//> using scalafix.dep io.github.mercurievv::scala-purrism-scalafix:0.5.0
```

For semantic rules, make sure Scala CLI emits SemanticDB:

```scala
//> using options "-Ysemanticdb"
```

Run:

```bash
scala-cli fix . --power
```

## Scalafix Configuration

Create `.scalafix.conf` in the project where you want to run the rules:

```hocon
rules = [
  TypeclassWeakening,
  PreferKleisli,
  PreferArrow,
  PreferCatsSyntax,
  SimplifyCatsExpressions,
  OpaqueTypePropagation
]
```

`PropagateOpaqueType` is configured separately, since it needs to be told what
to convert — see [Propagating an opaque type](#propagating-an-opaque-type).

### Rule Overview

- **`TypeclassWeakening`**: Weaken overly restrictive effect bounds (e.g. `Sync[F]` $\rightarrow$ `Monad[F]`) when only monadic operations are used.
- **`PreferKleisli`**: Refactor effectful functions into `Kleisli` compositions.
- **`PreferArrow`**: Prefer point-free `Arrow` composition over unpacking `Kleisli` with `.run`/`.apply` and stitching pieces together by hand. Handles linear chains (`andThen`), maps after run, and fan-out patterns (`&&&`).
- **`PreferCatsSyntax`**: Replace direct Cats typeclass calls such as `Applicative[F].pure(a)`, `MonadThrow[F].raiseError[A](e)`, `Functor[F].map(fa)(f)`, and `FlatMap[F].flatMap(fa)(f)` with Cats syntax.
- **`SimplifyCatsExpressions`**: Simplify common Cats and FP expressions using existing combinators, including `.void`, `.as`, `*>`, narrow `.mapN`, `Option(value)`, and `Either.cond`.
- **`OpaqueTypePropagation`**: Detect primitive value propagation chains (`String`, `Int`, `Long`, `BigDecimal`, etc.) across call trees and replace them with generated Scala 3 zero-cost `opaque type` domain wrappers. Discovers targets by parameter *name*, so it converts every domain-suffixed field it finds. For converting one specific field, prefer `PropagateOpaqueType`.
- **`PropagateOpaqueType`**: Replace one value's type with an `opaque type` and follow that value wherever it flows — parameters, fields, returns, container type arguments, `Kleisli` input tuples — wrapping where it is created and unwrapping where it crosses into an API you do not own. Targets are exact SemanticDB symbols rather than names, so unrelated `String`s that merely share a name are untouched. See [Propagating an opaque type](#propagating-an-opaque-type).

These are semantic rules, so the target project must compile with
SemanticDB enabled before the rule can run.

## Propagating an opaque type

`PropagateOpaqueType` starts from a seed symbol and computes the transitive
closure of everything that value reaches:

```hocon
rules = [ PropagateOpaqueType ]

PropagateOpaqueType.types = [
  {
    name = "BranchName"
    underlying = "scala/Predef.String#"
    definitionFile = "BusinessLogic.scala"
    seeds = [ "_empty_/TaskRun#branchName." ]
  }
]
```

- **`seeds`** are SemanticDB symbols, not names. Any of a case-class field's
  four symbols works — getter, constructor, `apply` or `copy` — they are treated
  as aliases. Run with `PropagateOpaqueType.debug = true` to print the symbols a
  file declares.
- **`definitionFile`** is where the `opaque type` and its companion are written,
  relative to the SemanticDB sourceroot. Leave it empty to declare the type
  yourself.
- **`widen`** resolves merge points (below).

The rule needs the whole program, so point Scalafix at the SemanticDB target
root and pass every source file:

```bash
scalafix --semanticdb-targetroots .semanticdb --sourceroot . \
         --files A.scala --files B.scala
```

`--files` takes one path per flag; repeat it. A file whose SemanticDB payload is
out of date is reported and left untouched rather than patched against a stale
view of the code.

### Merge points

Propagation runs forward, from a value to where it flows. When a parameter also
receives a value the closure does not cover, converting it would silently retype
that other value too — so the rule stops there, unwraps with `.value` at the
call site, and tells you:

```text
Git.scala:122: warning: _empty_/Git#branchExistsOnOrigin().[1,1] also receives
_empty_/Git#ensureBranch().(branchName), which the closure does not cover;
keeping the underlying type and unwrapping at the call site.
Add "_empty_/Git#ensureBranch().(branchName)" to `widen` to convert it too.
```

Whether the two values are really the same concept is a domain question, so the
rule reports rather than guesses. Adding the named symbol to `widen` pulls it in
and propagation continues through it.

## Publishing

Release and Sonatype Central setup notes are in [docs/PUBLISHING.md](docs/PUBLISHING.md).
