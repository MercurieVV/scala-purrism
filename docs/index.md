# scala-purrism Rules

This page is compiled with **mdoc**. The examples below are intentionally small:
the diff blocks show what each Scalafix rule rewrites, and the mdoc blocks keep
the after-shapes type-checked against Cats and Cats Effect.

```scala mdoc
import cats.*
import cats.data.{Kleisli, State}
import cats.effect.{IO, Sync}
import cats.syntax.all.*
```

```scala mdoc
val docsBuild = "mdoc compiles and runs the docs examples"
println(docsBuild)
```

## Setup

Add the published rule artifact:

```text
io.github.mercurievv:scala-purrism-scalafix_3:0.8.0
```

Enable SemanticDB in the target project:

```scala
scalacOptions += "-Ysemanticdb"
```

Choose rules in `.scalafix.conf`:

```hocon
rules = [
  TypelevelPurrism
]
```

For whole-project rewrites, run Scalafix over every relevant source file and
pass every SemanticDB target root. Recompile between stages when one rule
changes signatures that another rule reads semantically.

## Rules By Functionality

| group | rules |
| --- | --- |
| [Rule sets](#rule-sets) | `TypelevelPurrism`, `PreferTypeParameters` |
| [Effect boundaries](#effect-boundaries) | `TypeclassWeakening`, `SuspendSideEffects`, `PreferEffectIdioms` |
| [Cats expressions](#cats-expressions) | `PreferCatsSyntax`, `SimplifyCatsExpressions`, `PreferCatsFunctions` |
| [Kleisli and Arrow](#kleisli-and-arrow) | `PreferKleisli`, `PreferArrow` |
| [Polymorphic signatures](#polymorphic-signatures) | `PreferHKTTypeclasses`, `PreferContainerTypeclasses`, `PreferElementTypeclasses` |
| [Data and collection flow](#data-and-collection-flow) | `PropagateOpaqueType`, `PreferOptionIdioms`, `PreferIndexedMap`, `PreferStateThreading` |

### Rule Sets

#### TypelevelPurrism

Runs `TypeclassWeakening`, `PreferKleisli`, `PreferArrow`,
`PreferCatsFunctions`, `PreferTypeParameters`, `PreferCatsSyntax`, and
`SimplifyCatsExpressions`.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "rules = [ TypeclassWeakening, PreferKleisli, PreferArrow, PreferCatsSyntax ]",
  after = "rules = [ TypelevelPurrism ]"
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
rules = [ TypeclassWeakening, PreferCatsSyntax, SimplifyCatsExpressions ]
PreferArrow.aggressive = true
""",
  after = """
rules = [ TypelevelPurrism ]
PreferArrow.aggressive = true
"""
))
```

Since 0.8.0 the widening member is `PreferTypeParameters` — all three of
`PreferElementTypeclasses`, `PreferContainerTypeclasses` and
`PreferHKTTypeclasses` — where it was `PreferHKTTypeclasses` alone. Every
member key reaches them through the umbrella:

```hocon
PreferArrow.aggressive = true
PreferHKTTypeclasses.widenPublic = true
PreferContainerTypeclasses.crossFile = true
PreferElementTypeclasses.rewrite = false   # opt out: see below
```

`PreferElementTypeclasses` is the one member that rewrites a *body*:
`mkString` becomes `mkString_`, which renders elements with `Show` rather than
`toString`. Where the two disagree the program prints something different. Set
`PreferElementTypeclasses.rewrite = false` to keep the umbrella's widenings
purely at the signature.

#### PreferTypeParameters

Runs the three signature-widening rules together:
`PreferElementTypeclasses`, `PreferContainerTypeclasses`, and
`PreferHKTTypeclasses`.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "rules = [ PreferContainerTypeclasses, PreferElementTypeclasses, PreferHKTTypeclasses ]",
  after = "rules = [ PreferTypeParameters ]"
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
rules = [ PreferHKTTypeclasses, PreferContainerTypeclasses ]
PreferContainerTypeclasses.containers = [ "List", "Vector" ]
PreferHKTTypeclasses.containers = [ "*" ]
""",
  after = """
rules = [ PreferTypeParameters ]
PreferContainerTypeclasses.containers = [ "List", "Vector" ]
PreferHKTTypeclasses.containers = [ "*" ]
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
def labels[A](values: List[A]): List[String] =
  values.map(_.toString)

val out = labels[Int](List(1, 2))
""",
  after = """
def labels[S[_]: Functor, A](values: S[A]): S[String] =
  values.map(_.toString)

val out = labels(List(1, 2))
"""
))
```

### Effect Boundaries

#### TypeclassWeakening

Weakens over-strong effect bounds when the body only needs weaker Cats
capabilities.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
def bump[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)
""",
  after = """
def bump[F[_]: Monad](fa: F[Int]): F[Int] =
    fa.map(_ + 1)
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
def choose[F[_]: Concurrent, A](left: F[A], right: F[A]): F[A] =
  left.handleErrorWith(_ => right)
""",
  after = """
def choose[F[_]: MonadError[*[_], Throwable], A](left: F[A], right: F[A]): F[A] =
  left.handleErrorWith(_ => right)
"""
))
```

Config: none.

#### SuspendSideEffects

Reports side effects that a signature does not mention and rewrites eager
`pure(effect)` into suspended `delay(effect)` where an effect type supports it.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "Sync[F].pure(System.nanoTime())",
  after = "Sync[F].delay(System.nanoTime())"
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
def read[F[_]: Sync](path: java.nio.file.Path): F[String] =
  Sync[F].pure(java.nio.file.Files.readString(path))
""",
  after = """
def read[F[_]: Sync](path: java.nio.file.Path): F[String] =
  Sync[F].delay(java.nio.file.Files.readString(path))
"""
))
```

```hocon
SuspendSideEffects.rewrite = true
SuspendSideEffects.report = true
SuspendSideEffects.effects = [ "IO", "SyncIO", "Resource", "Stream", "Task", "EitherT", "OptionT", "Kleisli" ]
```

#### PreferEffectIdioms

Rewrites decidable effect idioms and reports shapes that need a wider program
decision.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
try work()
catch { case _: Throwable => fallback() }
""",
  after = """
try work()
catch { case scala.util.control.NonFatal(_) => fallback() }
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
try open()
finally close()
""",
  after = """
Resource.make(open())(_ => close()).use(identity)
"""
))
```

```hocon
PreferEffectIdioms.rewrite = true
PreferEffectIdioms.resources = true
PreferEffectIdioms.refs = true
```

### Cats Expressions

#### PreferCatsSyntax

Replaces direct Cats typeclass calls with Cats syntax.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "Applicative[F].pure(a)",
  after = "a.pure[F]"
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "MonadThrow[F].raiseError[A](error)",
  after = "error.raiseError[F, A]"
))
```

Config: none.

#### SimplifyCatsExpressions

Collapses common Cats expression patterns into existing combinators.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "fa.map(_ => ())",
  after = "fa.void"
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "fa.flatMap(a => a.pure[F])",
  after = "fa"
))
```

Config: none.

#### PreferCatsFunctions

Matches a project body against indexed Cats source functions and rewrites to
the winning public Cats function when evidence and evaluation order are safe.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
def lifted[F[_]: Applicative, A](a: A): F[A] =
  Applicative[F].pure(a)
""",
  after = """
def lifted[F[_]: Applicative, A](a: A): F[A] =
  a.pure[F]
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
def both[F[_]: Apply, A, B](fa: F[A], fb: F[B]): F[(A, B)] =
  Apply[F].map2(fa, fb)((a, b) => (a, b))
""",
  after = """
def both[F[_]: Apply, A, B](fa: F[A], fb: F[B]): F[(A, B)] =
  fa.product(fb)
"""
))
```

Config: none.

### Kleisli And Arrow

#### PreferKleisli

Turns effectful data-in/data-out methods into `Kleisli` values and re-splits
direct call sites.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
def load[F[_]: Monad](id: Long): F[String] =
  id.toString.pure[F]

val loaded: IO[String] = load[IO](42)
""",
  after = """
def load[F[_]: Monad]: Kleisli[F, Long, String] =
  Kleisli(id => id.toString.pure[F])

val loaded: IO[String] = load[IO].run(42)
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
def save[F[_]: Monad](name: String): F[Unit] =
  name.trim.pure[F].void

val saved: IO[Unit] = save[IO]("  Ada  ")
""",
  after = """
def save[F[_]: Monad]: Kleisli[F, String, Unit] =
  Kleisli(name => name.trim.pure[F].void)

val saved: IO[Unit] = save[IO].run("  Ada  ")
"""
))
```

```hocon
PreferKleisli.fileLocalOnly = false
PreferKleisli.crossFile = true
PreferKleisli.crossFileRoot = "."
```

#### PreferArrow

Rewrites hand-threaded `Kleisli` bodies into Arrow composition such as `>>>`,
`.map`, and `&&&`.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "Kleisli { id => load.run(id).map(_.trim) }",
  after = "load.map(_.trim)"
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "Kleisli { id => load.run(id).flatMap(validate.run) }",
  after = "load >>> validate"
))
```

```hocon
PreferArrow.aggressive = false
PreferArrow.reportSkips = false
```

### Polymorphic Signatures

#### PreferHKTTypeclasses

Widens a concrete unary constructor in a signature to `G[_]` with the weakest
Cats constraint used by the body.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
private def inspect(xs: Option[Int]): Option[Int] =
  xs.map(_ + 1)

val inspected = inspect(Some(1))
""",
  after = """
private def inspect[G[_]: Functor](xs: G[Int]): G[Int] =
  xs.map(_ + 1)

val inspected = inspect(Some(1))
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
private def handle(result: Either[String, Int]): Either[String, Int] =
  result.leftMap(_.trim)

val handled = handle(Left(" bad "))
""",
  after = """
private def handle[G[_, _]: Bifunctor](result: G[String, Int]): G[String, Int] =
  result.leftMap(_.trim)

val handled = handle(Left(" bad "))
"""
))
```

```hocon
PreferHKTTypeclasses.rewrite = true
PreferHKTTypeclasses.widenPublic = false
PreferHKTTypeclasses.maxConstraints = 2
PreferHKTTypeclasses.containers = [ "List", "Seq", "Vector", "IndexedSeq", "LazyList" ]
PreferHKTTypeclasses.crossFile = true
PreferHKTTypeclasses.crossFileTargetroots = [ "out" ]
```

#### PreferContainerTypeclasses

Widens concrete collection parameters to `S[_]` with the weakest Cats
collection constraint used by the body.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
private def names(users: List[String]): List[String] =
  users.map(_.toUpperCase)

val upper = names(List("ada"))
""",
  after = """
private def names[S[_]: Functor](users: S[String]): S[String] =
  users.map(_.toUpperCase)

val upper = names(List("ada"))
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
private def count(values: Vector[Int]): Int =
  values.foldLeft(0)(_ + _)

val total = count(Vector(1, 2, 3))
""",
  after = """
private def count[S[_]: Foldable](values: S[Int]): Int =
  values.foldLeft(0)(_ + _)

val total = count(Vector(1, 2, 3))
"""
))
```

```hocon
PreferContainerTypeclasses.rewrite = true
PreferContainerTypeclasses.widenPublic = false
PreferContainerTypeclasses.maxConstraints = 2
PreferContainerTypeclasses.containers = [ "List", "Seq", "Vector", "IndexedSeq", "LazyList" ]
PreferContainerTypeclasses.crossFile = true
PreferContainerTypeclasses.crossFileTargetroots = [ "out" ]
```

#### PreferElementTypeclasses

Handles collection operations whose Cats spelling depends on evidence for the
element, such as `mkString` to `mkString_`.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
private def rendered(rows: List[String]): String =
  rows.mkString("[", ",", "]")

val page = rendered(List("a", "b"))
""",
  after = """
private def rendered[S[_]: Foldable](rows: S[String]): String =
  rows.mkString_("[", ",", "]")

val page = rendered(List("a", "b"))
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
private def rendered(rows: Vector[Int]): String =
  rows.mkString(",")

val page = rendered(Vector(1, 2))
""",
  after = """
private def rendered[S[_]: Foldable](rows: S[Int]): String =
  rows.mkString_(",")

val page = rendered(Vector(1, 2))
"""
))
```

```hocon
PreferElementTypeclasses.rewrite = true
PreferElementTypeclasses.widenPublic = false
PreferElementTypeclasses.maxConstraints = 2
PreferElementTypeclasses.containers = [ "List", "Seq", "Vector", "IndexedSeq", "LazyList" ]
PreferElementTypeclasses.elements = [ "String", "Int", "Long", "Double", "Boolean" ]
PreferElementTypeclasses.crossFile = true
PreferElementTypeclasses.crossFileTargetroots = [ "out" ]
```

### Data And Collection Flow

#### PropagateOpaqueType

Introduces an opaque type and follows selected SemanticDB seed symbols through
value flow.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
final case class Task(branchName: String)
""",
  after = """
opaque type BranchName = String
object BranchName:
  def apply(value: String): BranchName = value
  extension (value: BranchName) def value: String = value

final case class Task(branchName: BranchName)
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
def checkout(branchName: String): IO[Unit] =
  IO.println(branchName)
""",
  after = """
def checkout(branchName: BranchName): IO[Unit] =
  IO.println(branchName.value)
"""
))
```

```hocon
PropagateOpaqueType.types = [
  {
    name = "BranchName"
    underlying = "scala/Predef.String#"
    definitionFile = "Task.scala"
    seeds = [ "_empty_/Task#branchName." ]
    widen = []
  }
]
PropagateOpaqueType.debug = false
PropagateOpaqueType.autoDiscover.enabled = false
```

#### PreferOptionIdioms

Rewrites nullable lookup and `Option.map(...).getOrElse(...)` shapes.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "option.map(render).getOrElse(default)",
  after = "option.fold(default)(render)"
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "if value == null then None else Some(value)",
  after = "Option(value)"
))
```

```hocon
PreferOptionIdioms.rewrite = true
PreferOptionIdioms.mouse = false
```

#### PreferIndexedMap

Rewrites index loops to direct collection operations, and effectful left folds
to `foldM`.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = "xs.indices.map(i => xs(i).toString)",
  after = "xs.map(x => x.toString)"
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
xs.zipWithIndex.map { case (x, i) => s"$i:$x" }
""",
  after = """
xs.mapWithIndex { case (x, i) => s"$i:$x" }
"""
))
```

```hocon
PreferIndexedMap.rewrite = true
```

#### PreferStateThreading

Rewrites pair-threaded state folds into Cats `State` where the fold only passes
state forward and collects output.

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
xs.foldLeft((0, Vector.empty[String])) { case ((s, out), x) =>
  (s + x, out :+ s"$x")
}
""",
  after = """
xs.traverse(x => State((s: Int) => (s + x, s"$x"))).run(0).value
"""
))
```

```scala mdoc:passthrough
print(docs.DocDiff.render(
  before = """
steps.foldLeft(seed) { case (state, step) =>
  step(state)
}
""",
  after = """
steps.traverse(step => State((state: Seed) => (step(state), ()))).runS(seed).value
"""
))
```

```hocon
PreferStateThreading.rewrite = true
PreferStateThreading.stateT = false
```

## Cross-File Keys

The signature-widening rules read these keys from their own config block:

```hocon
PreferContainerTypeclasses.crossFile = true
PreferContainerTypeclasses.crossFileRoot = "."
PreferContainerTypeclasses.crossFileTargetroots = [ "out" ]
```

Use `crossFileTargetroots = [ "target" ]` for sbt-style output, or `["out"]`
for Mill-style output. Compile first; stale SemanticDB means stale decisions.

## More

- [Engineering rules](RULES.md)
- [Golden fixtures](GOLDEN_FIXTURES.md)
- [Prefer Cats Functions contract](PREFER_CATS_FUNCTIONS.md)
- [Kleisli to Arrow catalogue](ARROW_PATTERNS.md)
- [Publishing](PUBLISHING.md)
