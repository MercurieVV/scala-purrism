# scala-purrism

Scalafix rules for refactoring Typelevel Scala code toward pure, polymorphic Cats
and Cats Effect style.

## Quickstart

Latest release:

```text
io.github.mercurievv:scala-purrism-scalafix_3:0.4.0
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
  "io.github.mercurievv" %% "scala-purrism-scalafix" % "0.4.0"

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
    mvn"io.github.mercurievv::scala-purrism-scalafix:0.4.0"
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
//> using scalafix.dep io.github.mercurievv::scala-purrism-scalafix:0.4.0
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
  PreferCatsSyntax,
  SimplifyCatsExpressions,
  OpaqueTypePropagation
]
```

### Rule Overview

- **`TypeclassWeakening`**: Weaken overly restrictive effect bounds (e.g. `Sync[F]` $\rightarrow$ `Monad[F]`) when only monadic operations are used.
- **`PreferKleisli`**: Refactor effectful functions into `Kleisli` compositions.
- **`PreferCatsSyntax`**: Replace direct Cats typeclass calls such as `Applicative[F].pure(a)`, `MonadThrow[F].raiseError[A](e)`, `Functor[F].map(fa)(f)`, and `FlatMap[F].flatMap(fa)(f)` with Cats syntax.
- **`SimplifyCatsExpressions`**: Simplify common Cats and FP expressions using existing combinators, including `.void`, `.as`, `*>`, narrow `.mapN`, `Option(value)`, and `Either.cond`.
- **`OpaqueTypePropagation`**: Detect primitive value propagation chains (`String`, `Int`, `Long`, `BigDecimal`, etc.) across call trees and replace them with generated Scala 3 zero-cost `opaque type` domain wrappers.

These are semantic rules, so the target project must compile with
SemanticDB enabled before the rule can run.

## Publishing

Release and Sonatype Central setup notes are in [docs/PUBLISHING.md](docs/PUBLISHING.md).
