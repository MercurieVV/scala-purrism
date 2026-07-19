# scala-purrism

Scalafix rules for refactoring Typelevel Scala code toward pure, polymorphic Cats
and Cats Effect style.

## Quickstart

Latest release:

```text
io.github.mercurievv:scala-purrism-scalafix_3:0.3.3
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
  "io.github.mercurievv" %% "scala-purrism-scalafix" % "0.3.3"

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
    mvn"io.github.mercurievv::scala-purrism-scalafix:0.3.3"
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
//> using scalafix.dep io.github.mercurievv::scala-purrism-scalafix:0.3.3
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
  OpaqueTypePropagation
]
```

### Rule Overview

- **`TypeclassWeakening`**: Weaken overly restrictive effect bounds (e.g. `Sync[F]` $\rightarrow$ `Monad[F]`) when only monadic operations are used.
- **`PreferKleisli`**: Refactor effectful functions into `Kleisli` compositions.
- **`OpaqueTypePropagation`**: Detect primitive value propagation chains (`String`, `Int`, `Long`, `BigDecimal`, etc.) across call trees and replace them with generated Scala 3 zero-cost `opaque type` domain wrappers.

These are semantic rules, so the target project must compile with
SemanticDB enabled before the rule can run.

## Publishing

Release and Sonatype Central setup notes are in [docs/PUBLISHING.md](docs/PUBLISHING.md).
