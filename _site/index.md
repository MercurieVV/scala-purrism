# scala-purrism Rules

This page is compiled with **mdoc**. The examples below are intentionally small:
the diff blocks show what each Scalafix rule rewrites, and the mdoc blocks keep
the after-shapes type-checked against Cats and Cats Effect.

```scala
import cats.*
import cats.data.Kleisli
import cats.effect.{IO, Sync}
import cats.syntax.all.*
```

```scala
val docsBuild = "mdoc compiles and runs the docs examples"
// docsBuild: String = "mdoc compiles and runs the docs examples"
println(docsBuild)
// mdoc compiles and runs the docs examples
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
| [Polymorphic signatures](#polymorphic-signatures) | `PreferPolymorphicTypeclasses`, `PreferPolymorphicCollections`, `PreferPolymorphicCollectionOps` |
| [Data and collection flow](#data-and-collection-flow) | `PropagateOpaqueType`, `PreferOptionIdioms`, `PreferIndexedMap` |

### Rule Sets

#### TypelevelPurrism

Runs `TypeclassWeakening`, `PreferKleisli`, `PreferArrow`,
`PreferCatsFunctions`, `PreferTypeParameters`, `PreferCatsSyntax`, and
`SimplifyCatsExpressions`.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line">rules = [ <span style="color:#8250df">Type</span><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">classWeakening, <span style="color:#8250df">PreferKleisli</span>, <span style="color:#8250df">PreferArrow</span>, <span style="color:#8250df">PreferCatsSyntax</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">levelPurrism</span> ]</span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line">rules = [ <span style="color:#8250df">Type</span><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">classWeakening, <span style="color:#8250df">PreferCatsSyntax</span>, <span style="color:#8250df">SimplifyCatsExpressions</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">levelPurrism</span> ]</span>
<span class="purrism-word-diff-line"><span style="color:#8250df">PreferArrow</span>.aggressive = <span style="color:#cf222e;font-weight:600">true</span></span></code></pre>
Since 0.8.0 the widening member is `PreferTypeParameters` — all three of
`PreferPolymorphicCollectionOps`, `PreferPolymorphicCollections` and
`PreferPolymorphicTypeclasses` — where it was `PreferPolymorphicTypeclasses` alone. Every
member key reaches them through the umbrella:

```hocon
PreferArrow.aggressive = true
PreferPolymorphicTypeclasses.widenPublic = true
PreferPolymorphicCollections.crossFile = true
PreferPolymorphicCollectionOps.rewrite = false   # opt out: see below
```

`PreferPolymorphicCollectionOps` is the one member that rewrites a *body*:
`mkString` becomes `mkString_`, which renders elements with `Show` rather than
`toString`. Where the two disagree the program prints something different. Set
`PreferPolymorphicCollectionOps.rewrite = false` to keep the umbrella's widenings
purely at the signature.

#### PreferTypeParameters

Runs the three signature-widening rules together:
`PreferPolymorphicCollectionOps`, `PreferPolymorphicCollections`, and
`PreferPolymorphicTypeclasses`.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line">rules = [ <span style="color:#8250df">Prefer</span><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">PolymorphicCollections</span>, <span style="color:#8250df">PreferPolymorphicCollectionOps</span>, <span style="color:#8250df">PreferPolymorphicTypeclasse</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">TypeParameter</span></span>s ]</span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line">rules = [ <span style="color:#8250df">Prefer</span><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">PolymorphicTypeclasses</span>, <span style="color:#8250df">PreferPolymorphicCollection</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">TypeParameter</span></span>s ]</span>
<span class="purrism-word-diff-line"><span style="color:#8250df">PreferPolymorphicCollections</span>.containers = [ <span style="color:#0a3069">&quot;List&quot;</span>, <span style="color:#0a3069">&quot;Vector&quot;</span> ]</span>
<span class="purrism-word-diff-line"><span style="color:#8250df">PreferPolymorphicTypeclasses</span>.containers = [ <span style="color:#0a3069">&quot;*&quot;</span> ]</span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">def</span> labels[<span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">S</span>[_]: <span style="color:#8250df">Functor</span>, </span><span style="color:#8250df">A</span>](values: <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">List</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">S</span></span>[<span style="color:#8250df">A</span>]): <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">List</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">S</span></span>[<span style="color:#8250df">String</span>] =</span>
<span class="purrism-word-diff-line">  values.map(_.toString)</span>
<span class="purrism-word-diff-line"></span>
<span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">val</span> out = labels<span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">[<span style="color:#8250df">Int</span>]</span>(<span style="color:#8250df">List</span>(<span style="color:#0550ae">1</span>, <span style="color:#0550ae">2</span>))</span></code></pre>
### Effect Boundaries

#### TypeclassWeakening

Weakens over-strong effect bounds when the body only needs weaker Cats
capabilities.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">def</span> bump[<span style="color:#8250df">F</span>[_]: <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">Sync</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">Monad</span></span>](fa: <span style="color:#8250df">F</span>[<span style="color:#8250df">Int</span>]): <span style="color:#8250df">F</span>[<span style="color:#8250df">Int</span>] =</span>
<span class="purrism-word-diff-line">    fa.map(_ + <span style="color:#0550ae">1</span>)</span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">def</span> choose[<span style="color:#8250df">F</span>[_]: <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">Concurrent</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">MonadError</span>[*[_], <span style="color:#8250df">Throwable</span>]</span>, <span style="color:#8250df">A</span>](left: <span style="color:#8250df">F</span>[<span style="color:#8250df">A</span>], right: <span style="color:#8250df">F</span>[<span style="color:#8250df">A</span>]): <span style="color:#8250df">F</span>[<span style="color:#8250df">A</span>] =</span>
<span class="purrism-word-diff-line">  left.handleErrorWith(_ =&gt; right)</span></code></pre>
Config: none.

#### SuspendSideEffects

Reports side effects that a signature does not mention and rewrites eager
`pure(effect)` into suspended `delay(effect)` where an effect type supports it.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#8250df">Sync</span>[<span style="color:#8250df">F</span>].<span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">pure</span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">delay</span>(<span style="color:#8250df">System</span>.nanoTime())</span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">def</span> read[<span style="color:#8250df">F</span>[_]: <span style="color:#8250df">Sync</span>](path: <span style="color:#8250df">Path</span>): <span style="color:#8250df">F</span>[<span style="color:#8250df">String</span>] =</span>
<span class="purrism-word-diff-line">  <span style="color:#8250df">Sync</span>[<span style="color:#8250df">F</span>].<span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">pure</span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">delay</span>(<span style="color:#8250df">Files</span>.readString(path))</span></code></pre>
```hocon
SuspendSideEffects.rewrite = true
SuspendSideEffects.report = true
SuspendSideEffects.effects = [ "IO", "SyncIO", "Resource", "Stream", "Task", "EitherT", "OptionT", "Kleisli" ]
```

#### PreferEffectIdioms

Rewrites decidable effect idioms and reports shapes that need a wider program
decision.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">try</span> work()</span>
<span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">catch</span> { <span style="color:#cf222e;font-weight:600">case</span> <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">_: <span style="color:#8250df">Throwable</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">NonFatal</span>(_)</span> =&gt; fallback() }</span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#cf222e;font-weight:600">try</span> open()</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">Resource</span>.make(open())(_ =&gt; close()).use(identity)</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">finally close()</span></span></code></pre>
```hocon
PreferEffectIdioms.rewrite = true
PreferEffectIdioms.resources = true
PreferEffectIdioms.refs = true
```

### Cats Expressions

#### PreferCatsSyntax

Replaces direct Cats typeclass calls with Cats syntax.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">Applicative</span>[<span style="color:#8250df">F</span>].pure(a)</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">a.pure[<span style="color:#8250df">F</span>]</span></span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">MonadThrow</span>[<span style="color:#8250df">F</span>].raiseError[<span style="color:#8250df">A</span>](error)</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">error.raiseError[<span style="color:#8250df">F</span>, <span style="color:#8250df">A</span>]</span></span></code></pre>
Config: none.

#### SimplifyCatsExpressions

Collapses common Cats expression patterns into existing combinators.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">fa.map(_ =&gt; ())</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">fa.void</span></span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">fa.flatMap(a =&gt; a.pure[<span style="color:#8250df">F</span>])</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">fa</span></span></code></pre>
Config: none.

#### PreferCatsFunctions

Matches a project body against indexed Cats source functions and rewrites to
the winning public Cats function when evidence and evaluation order are safe.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">def</span> lifted[<span style="color:#8250df">F</span>[_]: <span style="color:#8250df">Applicative</span>, <span style="color:#8250df">A</span>](a: <span style="color:#8250df">A</span>): <span style="color:#8250df">F</span>[<span style="color:#8250df">A</span>] =</span>
<span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">  <span style="color:#8250df">Applicative</span>[<span style="color:#8250df">F</span>].pure(a)</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">  a.pure[<span style="color:#8250df">F</span>]</span></span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">def</span> both[<span style="color:#8250df">F</span>[_]: <span style="color:#8250df">Apply</span>, <span style="color:#8250df">A</span>, <span style="color:#8250df">B</span>](fa: <span style="color:#8250df">F</span>[<span style="color:#8250df">A</span>], fb: <span style="color:#8250df">F</span>[<span style="color:#8250df">B</span>]): <span style="color:#8250df">F</span>[(<span style="color:#8250df">A</span>, <span style="color:#8250df">B</span>)] =</span>
<span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">  <span style="color:#8250df">Apply</span>[<span style="color:#8250df">F</span>].map2(fa, fb)((a, b) =&gt; (a, b))</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">  fa.product(fb)</span></span></code></pre>
Config: none.

### Kleisli And Arrow

#### PreferKleisli

Turns effectful data-in/data-out methods into `Kleisli` values and re-splits
direct call sites.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">def</span> load[<span style="color:#8250df">F</span>[_]: <span style="color:#8250df">Monad</span>]<span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">(id: <span style="color:#8250df">Long</span>): <span style="color:#8250df">F</span>[</span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">: <span style="color:#8250df">Kleisli</span>[<span style="color:#8250df">F</span>, <span style="color:#8250df">Long</span>, </span><span style="color:#8250df">String</span>] =</span>
<span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">  id.toString.pure[<span style="color:#8250df">F</span>]</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">  <span style="color:#8250df">Kleisli</span>(id =&gt; id.toString.pure[<span style="color:#8250df">F</span>])</span></span>
<span class="purrism-word-diff-line"></span>
<span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">val</span> loaded: <span style="color:#8250df">IO</span>[<span style="color:#8250df">String</span>] = load[<span style="color:#8250df">IO</span>]<span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">.run</span>(<span style="color:#0550ae">42</span>)</span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">def</span> save[<span style="color:#8250df">F</span>[_]: <span style="color:#8250df">Monad</span>]<span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">(name: <span style="color:#8250df">String</span>): <span style="color:#8250df">F</span>[</span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">: <span style="color:#8250df">Kleisli</span>[<span style="color:#8250df">F</span>, <span style="color:#8250df">String</span>, </span><span style="color:#8250df">Unit</span>] =</span>
<span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">  name.trim.pure[<span style="color:#8250df">F</span>].void</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">  <span style="color:#8250df">Kleisli</span>(name =&gt; name.trim.pure[<span style="color:#8250df">F</span>].void)</span></span>
<span class="purrism-word-diff-line"></span>
<span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">val</span> saved: <span style="color:#8250df">IO</span>[<span style="color:#8250df">Unit</span>] = save[<span style="color:#8250df">IO</span>]<span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">.run</span>(<span style="color:#0a3069">&quot;  Ada  &quot;</span>)</span></code></pre>
```hocon
PreferKleisli.fileLocalOnly = false
PreferKleisli.crossFile = true
PreferKleisli.crossFileRoot = "."
```

#### PreferArrow

Rewrites hand-threaded `Kleisli` bodies into Arrow composition such as `>>>`,
`.map`, and `&&&`.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">Kleisli</span> { id =&gt; load.run(id).map(_.trim) }</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">load.map(_.trim)</span></span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">Kleisli</span> { id =&gt; load.run(id).flatMap(validate.run) }</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">load &gt;&gt;&gt; validate</span></span></code></pre>
```hocon
PreferArrow.aggressive = false
PreferArrow.reportSkips = false
```

### Polymorphic Signatures

Three rules widen one concrete type constructor in a signature to a type
parameter, each over a different subject — never the same parameter twice:

| rule | subject | touches the body? |
| --- | --- | --- |
| `PreferPolymorphicTypeclasses` | any unary constructor Cats has — `Eval`, `Show`, `Option`, domain types — except collections | no |
| `PreferPolymorphicCollections` | `List`/`Seq`/`Vector`/... (`containers`) | no |
| `PreferPolymorphicCollectionOps` | same collections, only bodies calling `mkString`/`sum`-like ops | **yes** — renames the call too, e.g. `mkString` → `mkString_` |

One file, one method per rule, run through the umbrella `PreferTypeParameters`:

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">final</span> <span style="color:#cf222e;font-weight:600">class</span> <span style="color:#8250df">Summary</span> {</span>
<span class="purrism-word-diff-line">  <span style="color:#cf222e;font-weight:600">private</span> <span style="color:#cf222e;font-weight:600">def</span> names<span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">[<span style="color:#8250df">S</span>[_]: <span style="color:#8250df">Functor</span>]</span>(users: <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">List</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">S</span></span>[<span style="color:#8250df">String</span>]): <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">List</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">S</span></span>[<span style="color:#8250df">String</span>] =</span>
<span class="purrism-word-diff-line">    users.map(user =&gt; user.toUpperCase)</span>
<span class="purrism-word-diff-line"></span>
<span class="purrism-word-diff-line">  <span style="color:#cf222e;font-weight:600">private</span> <span style="color:#cf222e;font-weight:600">def</span> rendered<span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">[<span style="color:#8250df">S</span>[_]: <span style="color:#8250df">Foldable</span>]</span>(rows: <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">List</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">S</span></span>[<span style="color:#8250df">String</span>]): <span style="color:#8250df">String</span> =</span>
<span class="purrism-word-diff-line">    rows.mkString<span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">_</span>(<span style="color:#0a3069">&quot;[&quot;</span>, <span style="color:#0a3069">&quot;,&quot;</span>, <span style="color:#0a3069">&quot;]&quot;</span>)</span>
<span class="purrism-word-diff-line"></span>
<span class="purrism-word-diff-line">  <span style="color:#cf222e;font-weight:600">private</span> <span style="color:#cf222e;font-weight:600">def</span> duplicate<span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">[<span style="color:#8250df">G</span>[_]: <span style="color:#8250df">Comonad</span>]</span>(e: <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">Eval</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">G</span></span>[<span style="color:#8250df">Int</span>]): <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">Eval</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">G</span></span>[<span style="color:#8250df">Int</span>] =</span>
<span class="purrism-word-diff-line">    e.coflatMap(w =&gt; w.extract + <span style="color:#0550ae">1</span>)</span>
<span class="purrism-word-diff-line">}</span></code></pre>
`names` → `PreferPolymorphicCollections`. `rendered` → `PreferPolymorphicCollectionOps`
(signature *and* the call). `duplicate` → `PreferPolymorphicTypeclasses` (not a collection).

```hocon
PreferPolymorphicTypeclasses.containers = [ "List", "Seq", "Vector", "IndexedSeq", "LazyList" ]
PreferPolymorphicTypeclasses.widenPublic = false
PreferPolymorphicTypeclasses.maxConstraints = 2

PreferPolymorphicCollections.containers = [ "List", "Seq", "Vector", "IndexedSeq", "LazyList" ]
PreferPolymorphicCollections.widenPublic = false
PreferPolymorphicCollections.maxConstraints = 2

PreferPolymorphicCollectionOps.containers = [ "List", "Seq", "Vector", "IndexedSeq", "LazyList" ]
PreferPolymorphicCollectionOps.elements = [ "String", "Int", "Long", "Double", "Boolean", "..." ]
PreferPolymorphicCollectionOps.rewrite = true  # false keeps the other two widenings, drops the body rewrite
```

All three also take `rewrite`, `crossFile`, and `crossFileTargetroots`.

### Data And Collection Flow

#### PropagateOpaqueType

Introduces an opaque type and follows selected SemanticDB seed symbols through
value flow.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#cf222e;font-weight:600">opaque</span> <span style="color:#cf222e;font-weight:600">type</span> <span style="color:#8250df">BranchName</span> = <span style="color:#8250df">String</span></span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#cf222e;font-weight:600">object</span> <span style="color:#8250df">BranchName</span>:</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">  <span style="color:#cf222e;font-weight:600">def</span> apply(value: <span style="color:#8250df">String</span>): <span style="color:#8250df">BranchName</span> = value</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">  <span style="color:#cf222e;font-weight:600">extension</span> (value: <span style="color:#8250df">BranchName</span>) <span style="color:#cf222e;font-weight:600">def</span> value: <span style="color:#8250df">String</span> = value</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"></span></span>
<span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">final</span> <span style="color:#cf222e;font-weight:600">case</span> <span style="color:#cf222e;font-weight:600">class</span> <span style="color:#8250df">Task</span>(branchName: <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">String</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">BranchName</span></span>)</span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="color:#cf222e;font-weight:600">def</span> checkout(branchName: <span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#8250df">String</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">BranchName</span></span>): <span style="color:#8250df">IO</span>[<span style="color:#8250df">Unit</span>] =</span>
<span class="purrism-word-diff-line">  <span style="color:#8250df">IO</span>.println(branchName<span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">.value</span>)</span></code></pre>
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

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">option.map(render).getOrElse(default)</span></span>
<span class="purrism-word-diff-line"><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">option.fold(default)(render)</span></span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line"><span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px"><span style="color:#cf222e;font-weight:600">if</span> value == null <span style="color:#cf222e;font-weight:600">then</span> <span style="color:#8250df">None</span> <span style="color:#cf222e;font-weight:600">else</span> <span style="color:#8250df">Some</span></span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px"><span style="color:#8250df">Option</span></span>(value)</span></code></pre>
```hocon
PreferOptionIdioms.rewrite = true
PreferOptionIdioms.mouse = false
```

#### PreferIndexedMap

Rewrites index loops to direct collection operations, and effectful left folds
to `foldM`.

<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line">xs.<span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">indices.map(i =&gt; xs(i)</span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">map(x =&gt; x</span>.toString)</span></code></pre>
<pre class="purrism-word-diff"><code><span class="purrism-word-diff-line">xs.<span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">zipWithIndex.map</span><span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">mapWithIndex</span> { <span style="color:#cf222e;font-weight:600">case</span> (x, i) =&gt; s<span style="color:#0a3069">&quot;$i:$x&quot;</span> }</span></code></pre>
```hocon
PreferIndexedMap.rewrite = true
```

## Cross-File Keys

The signature-widening rules read these keys from their own config block:

```hocon
PreferPolymorphicCollections.crossFile = true
PreferPolymorphicCollections.crossFileRoot = "."
PreferPolymorphicCollections.crossFileTargetroots = [ "out" ]
```

Use `crossFileTargetroots = [ "target" ]` for sbt-style output, or `["out"]`
for Mill-style output. Compile first; stale SemanticDB means stale decisions.

## More

- [Engineering rules](RULES.md)
- [Golden fixtures](GOLDEN_FIXTURES.md)
- [Prefer Cats Functions contract](PREFER_CATS_FUNCTIONS.md)
- [Kleisli to Arrow catalogue](ARROW_PATTERNS.md)
- [Publishing](PUBLISHING.md)
