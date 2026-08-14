# scala-purrism

Scalafix semantic rules for refactoring Typelevel Scala code toward pure,
polymorphic Cats and Cats Effect style.

Full rule reference: [mdoc site](website/docs/index.md) ([source](docs/index.md)).
Run `rtk mill docs.run` to render it.

## Quickstart

Latest release:

```text
io.github.mercurievv:scala-purrism-scalafix_3:0.8.0
```

The published artifact targets Scala 3 and Scalafix `0.14.7`.

### sbt

Add sbt-scalafix to `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")
```

Configure SemanticDB and the rule dependency in `build.sbt`:

```scala
ThisBuild / scalacOptions += "-Ysemanticdb"

ThisBuild / scalafixDependencies +=
  "io.github.mercurievv" %% "scala-purrism-scalafix" % "0.8.0"
```

Create `.scalafix.conf`:

```hocon
rules = [
  TypelevelPurrism
]
```

Run:

```bash
sbt scalafix
```

### Mill

Add Mill Scalafix support and the rule dependency to `build.mill`:

```scala
//| mvnDeps:
//| - com.goyeau::mill-scalafix::0.6.0

import com.goyeau.mill.scalafix.ScalafixModule
import mill.*, scalalib.*

object app extends ScalaModule, ScalafixModule {
  def scalaVersion = "3.8.4"
  def scalacOptions = Seq("-Ysemanticdb")
  def scalafixIvyDeps = Seq(
    mvn"io.github.mercurievv::scala-purrism-scalafix:0.8.0"
  )
}
```

Create `.scalafix.conf`:

```hocon
rules = [
  TypelevelPurrism
]
```

Run:

```bash
./mill app.fix
```

### Scala CLI

Add directives:

```scala
//> using options "-Ysemanticdb"
//> using scalafix.dep io.github.mercurievv::scala-purrism-scalafix:0.8.0
```

Create `.scalafix.conf`:

```hocon
rules = [
  TypelevelPurrism
]
```

Run:

```bash
scala-cli fix . --power
```

### Scalafix CLI

Use the CLI when a rule needs a whole-project view or when your build tool runs
Scalafix module by module.

```bash
cs install scalafix

scalafix \
  --rules TypelevelPurrism \
  --tool-classpath "$(cs fetch -p io.github.mercurievv::scala-purrism-scalafix:0.8.0)" \
  --semanticdb-targetroots target/scala-3.8.4/classes \
  --sourceroot . \
  --files src/main/scala/example/App.scala
```

`--semanticdb-targetroots` must point at the directory containing
`META-INF/semanticdb`, usually the compiler output directory.
