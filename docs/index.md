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

## Rules

| rule | purpose | config |
| --- | --- | --- |
| [`TypelevelPurrism`](#typelevelpurrism) | umbrella for the core Typelevel rewrites | member rule config |
| [`TypeclassWeakening`](#typeclassweakening) | weaker Cats Effect constraints | none |
| [`PreferKleisli`](#preferkleisli) | effectful defs to `Kleisli` | `fileLocalOnly`, `crossFile`, `crossFileRoot` |
| [`PreferArrow`](#preferarrow) | hand-threaded `Kleisli` to Arrow syntax | `aggressive`, `reportSkips` |
| [`PreferCatsFunctions`](#prefercatsfunctions) | bodies matching Cats functions to the Cats function | none |
| [`PreferHKTTypeclasses`](#preferhkttypeclasses) | concrete unary constructors to `G[_]` | `rewrite`, `widenPublic`, `maxConstraints`, `containers`, cross-file keys |
| [`PreferCatsSyntax`](#prefercatssyntax) | typeclass calls to syntax | none |
| [`SimplifyCatsExpressions`](#simplifycatsexpressions) | common Cats expression simplifications | none |
| [`PropagateOpaqueType`](#propagateopaquetype) | propagate an opaque type through value flow | `types`, `debug`, `autoDiscover` |
| [`PreferEffectIdioms`](#prefereffectidioms) | effect/catch/resource/reference idioms | `rewrite`, `resources`, `refs` |
| [`PreferOptionIdioms`](#preferoptionidioms) | null and `Option` lookup idioms | `rewrite`, `mouse` |
| [`PreferIndexedMap`](#preferindexedmap) | index loops and effectful folds | `rewrite` |
| [`PreferContainerTypeclasses`](#prefercontainertypeclasses) | collection parameters to `S[_]` | `rewrite`, `widenPublic`, `maxConstraints`, `containers`, cross-file keys |
| [`PreferTypeParameters`](#prefertypeparameters) | umbrella for element/container/HKT widening | member rule config |
| [`PreferStateThreading`](#preferstatethreading) | pair-threaded folds to `State` | `rewrite`, `stateT` |
| [`SuspendSideEffects`](#suspendsideeffects) | unsuspended side effects | `rewrite`, `report`, `effects` |
| [`PreferElementTypeclasses`](#preferelementtypeclasses) | element-driven Cats operations | `rewrite`, `widenPublic`, `maxConstraints`, `containers`, `elements`, cross-file keys |

### TypelevelPurrism

Runs `TypeclassWeakening`, `PreferKleisli`, `PreferArrow`,
`PreferCatsFunctions`, `PreferHKTTypeclasses`, `PreferCatsSyntax`, and
`SimplifyCatsExpressions`.

```diff
- rules = [ TypeclassWeakening, PreferKleisli, PreferArrow, PreferCatsSyntax ]
+ rules = [ TypelevelPurrism ]
```

```hocon
PreferArrow.aggressive = true
PreferHKTTypeclasses.widenPublic = true
```

### TypeclassWeakening

Weakens over-strong effect bounds when the body only needs weaker Cats
capabilities.

```diff
- def bump[F[_]: Sync](fa: F[Int]): F[Int] =
+ def bump[F[_]: Monad](fa: F[Int]): F[Int] =
    fa.map(_ + 1)
```

```scala mdoc:compile-only
def typeclassWeakeningAfter[F[_]: Monad](fa: F[Int]): F[Int] =
  fa.map(_ + 1)
```

Config: none.

### PreferKleisli

Turns effectful data-in/data-out methods into `Kleisli` values and re-splits
direct call sites.

```diff
- def load[F[_]: Monad](id: Long): F[String] =
-   id.toString.pure[F]
+ def load[F[_]: Monad]: Kleisli[F, Long, String] =
+   Kleisli(id => id.toString.pure[F])
```

```scala mdoc:compile-only
def preferKleisliAfter[F[_]: Monad]: Kleisli[F, Long, String] =
  Kleisli(id => id.toString.pure[F])
```

```hocon
PreferKleisli.fileLocalOnly = false
PreferKleisli.crossFile = true
PreferKleisli.crossFileRoot = "."
```

### PreferArrow

Rewrites hand-threaded `Kleisli` bodies into Arrow composition such as `>>>`,
`.map`, and `&&&`.

```diff
- Kleisli { id => load.run(id).map(_.trim) }
+ load.map(_.trim)
```

```scala mdoc:compile-only
def preferArrowAfter[F[_]: Functor](
    load: Kleisli[F, Long, String]
): Kleisli[F, Long, String] =
  load.map(_.trim)
```

```hocon
PreferArrow.aggressive = false
PreferArrow.reportSkips = false
```

### PreferCatsFunctions

Matches a project body against indexed Cats source functions and rewrites to
the winning public Cats function when evidence and evaluation order are safe.

```diff
- def lifted[F[_]: Applicative, A](a: A): F[A] =
-   Applicative[F].pure(a)
+ def lifted[F[_]: Applicative, A](a: A): F[A] =
+   a.pure[F]
```

```scala mdoc:compile-only
def preferCatsFunctionsAfter[F[_]: Applicative, A](a: A): F[A] =
  a.pure[F]
```

Config: none.

### PreferHKTTypeclasses

Widens a concrete unary constructor in a signature to `G[_]` with the weakest
Cats constraint used by the body.

```diff
- private def inspect(xs: Option[Int]): Option[Int] =
-   xs.map(_ + 1)
+ private def inspect[G[_]: Functor](xs: G[Int]): G[Int] =
+   xs.map(_ + 1)
```

```scala mdoc:compile-only
private def preferHktAfter[G[_]: Functor](xs: G[Int]): G[Int] =
  xs.map(_ + 1)
```

```hocon
PreferHKTTypeclasses.rewrite = true
PreferHKTTypeclasses.widenPublic = false
PreferHKTTypeclasses.maxConstraints = 2
PreferHKTTypeclasses.containers = [ "List", "Seq", "Vector", "IndexedSeq", "LazyList" ]
PreferHKTTypeclasses.crossFile = true
PreferHKTTypeclasses.crossFileTargetroots = [ "out" ]
```

### PreferCatsSyntax

Replaces direct Cats typeclass calls with Cats syntax.

```diff
- Applicative[F].pure(a)
+ a.pure[F]
```

```scala mdoc:compile-only
def preferCatsSyntaxAfter[F[_]: Applicative, A](a: A): F[A] =
  a.pure[F]
```

Config: none.

### SimplifyCatsExpressions

Collapses common Cats expression patterns into existing combinators.

```diff
- fa.map(_ => ())
+ fa.void
```

```scala mdoc:compile-only
def simplifyCatsExpressionsAfter[F[_]: Functor, A](fa: F[A]): F[Unit] =
  fa.void
```

Config: none.

### PropagateOpaqueType

Introduces an opaque type and follows selected SemanticDB seed symbols through
value flow.

```diff
- final case class Task(branchName: String)
+ opaque type BranchName = String
+ object BranchName:
+   def apply(value: String): BranchName = value
+   extension (value: BranchName) def value: String = value
+
+ final case class Task(branchName: BranchName)
```

```scala mdoc:compile-only
object OpaqueDocs:
  opaque type BranchName = String
  object BranchName:
    def apply(value: String): BranchName = value
    extension (value: BranchName) def value: String = value
  final case class Task(branchName: BranchName)
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

### PreferEffectIdioms

Rewrites decidable effect idioms and reports shapes that need a wider program
decision.

```diff
- try work()
- catch { case _: Throwable => fallback() }
+ try work()
+ catch { case scala.util.control.NonFatal(_) => fallback() }
```

```scala mdoc:compile-only
import scala.util.control.NonFatal

def preferEffectIdiomsAfter(work: () => Int, fallback: () => Int): Int =
  try work()
  catch { case NonFatal(_) => fallback() }
```

```hocon
PreferEffectIdioms.rewrite = true
PreferEffectIdioms.resources = true
PreferEffectIdioms.refs = true
```

### PreferOptionIdioms

Rewrites nullable lookup and `Option.map(...).getOrElse(...)` shapes.

```diff
- option.map(render).getOrElse(default)
+ option.fold(default)(render)
```

```scala mdoc:compile-only
def preferOptionIdiomsAfter(option: Option[Int], default: String): String =
  option.fold(default)(_.toString)
```

```hocon
PreferOptionIdioms.rewrite = true
PreferOptionIdioms.mouse = false
```

### PreferIndexedMap

Rewrites index loops to direct collection operations, and effectful left folds
to `foldM`.

```diff
- xs.indices.map(i => xs(i).toString)
+ xs.map(x => x.toString)
```

```scala mdoc:compile-only
def preferIndexedMapAfter(xs: Vector[Int]): Vector[String] =
  xs.map(x => x.toString)
```

```hocon
PreferIndexedMap.rewrite = true
```

### PreferContainerTypeclasses

Widens concrete collection parameters to `S[_]` with the weakest Cats
collection constraint used by the body.

```diff
- private def names(users: List[String]): List[String] =
-   users.map(_.toUpperCase)
+ private def names[S[_]: Functor](users: S[String]): S[String] =
+   users.map(_.toUpperCase)
```

```scala mdoc:compile-only
private def preferContainerAfter[S[_]: Functor](users: S[String]): S[String] =
  users.map(_.toUpperCase)
```

```hocon
PreferContainerTypeclasses.rewrite = true
PreferContainerTypeclasses.widenPublic = false
PreferContainerTypeclasses.maxConstraints = 2
PreferContainerTypeclasses.containers = [ "List", "Seq", "Vector", "IndexedSeq", "LazyList" ]
PreferContainerTypeclasses.crossFile = true
PreferContainerTypeclasses.crossFileTargetroots = [ "out" ]
```

### PreferTypeParameters

Runs the three signature-widening rules together:
`PreferElementTypeclasses`, `PreferContainerTypeclasses`, and
`PreferHKTTypeclasses`.

```diff
- rules = [ PreferContainerTypeclasses, PreferElementTypeclasses, PreferHKTTypeclasses ]
+ rules = [ PreferTypeParameters ]
```

```hocon
PreferContainerTypeclasses.containers = [ "*" ]
PreferElementTypeclasses.elements = [ "String", "Int", "Money" ]
PreferHKTTypeclasses.containers = [ "*" ]
```

### PreferStateThreading

Rewrites pair-threaded state folds into Cats `State` where the fold only passes
state forward and collects output.

```diff
- xs.foldLeft((0, Vector.empty[String])) { case ((s, out), x) =>
-   (s + x, out :+ s"$x")
- }
+ xs.traverse(x => State((s: Int) => (s + x, s"$x"))).run(0).value
```

```scala mdoc:compile-only
def preferStateThreadingAfter(xs: List[Int]): (Int, List[String]) =
  xs.traverse(x => State((s: Int) => (s + x, s"$x"))).run(0).value
```

```hocon
PreferStateThreading.rewrite = true
PreferStateThreading.stateT = false
```

### SuspendSideEffects

Reports side effects that a signature does not mention and rewrites eager
`pure(effect)` into suspended `delay(effect)` where an effect type supports it.

```diff
- Sync[F].pure(System.nanoTime())
+ Sync[F].delay(System.nanoTime())
```

```scala mdoc:compile-only
def suspendSideEffectsAfter[F[_]: Sync]: F[Long] =
  Sync[F].delay(System.nanoTime())
```

```hocon
SuspendSideEffects.rewrite = true
SuspendSideEffects.report = true
SuspendSideEffects.effects = [ "IO", "SyncIO", "Resource", "Stream", "Task", "EitherT", "OptionT", "Kleisli" ]
```

### PreferElementTypeclasses

Handles collection operations whose Cats spelling depends on evidence for the
element, such as `mkString` to `mkString_`.

```diff
- private def rendered(rows: List[String]): String =
-   rows.mkString("[", ",", "]")
+ private def rendered[S[_]: Foldable](rows: S[String]): String =
+   rows.mkString_("[", ",", "]")
```

```scala mdoc:compile-only
private def preferElementAfter[S[_]: Foldable](rows: S[String]): String =
  rows.mkString_("[", ",", "]")
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
