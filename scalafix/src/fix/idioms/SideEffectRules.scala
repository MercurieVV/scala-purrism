package fix.idioms

import scala.meta._

import fix.catsexpr.CatsFacts

/** Effects a signature does not mention.
  *
  * `def write(line: String): Unit` says it computes nothing and returns
  * nothing. If its body prints, opens a file, or reads the clock, the type is
  * not describing the method: the effect happens when the method is *called*,
  * so nothing can sequence it, retry it, or run it somewhere else. `F[Unit]`
  * under `Sync` says the same thing truthfully, and the value can then be
  * passed around before anything happens.
  *
  * The report is the rule's main output, because moving a method to `F[Unit]`
  * changes its signature and every call site with it -- a project-wide decision
  * this rule is not handed the information to make (`docs/RULES.md`).
  *
  * One shape rewrites, because there it is a defect rather than a style: an
  * effect inside `pure`. `IO.pure(System.nanoTime())` reads the clock *once*,
  * when the `IO` is built, and every run of that value replays the same number.
  * `IO.delay` is what was meant, and the two differ in behaviour rather than in
  * how they read.
  */
private[fix] object SideEffectRules {

  import TermShapes._

  def unsuspendedEffectMessage(count: Int): String = {
    val effects = if (count == 1) "an effect" else s"$count effects"
    s"this method performs $effects its type does not mention. A body that " +
      "touches the world belongs in `F[_]: Sync` -- as it stands the effect " +
      "happens on call, so nothing can sequence or retry it."
  }

  def rewrites(
      tree: Tree,
      facts: SideEffectFacts,
      catsFacts: CatsFacts
  ): List[IdiomRewrite] =
    tree.collect { case term: Term =>
      eagerPure(term, facts, catsFacts)
    }.flatten

  /** One finding per method, not per effect.
    *
    * A WAV header writer calls `out.write` twenty times; the reader's decision
    * is not made twenty times. It is made once, about the method: should this
    * be `F[Unit]`. Reporting each call buries that question under its evidence.
    *
    * Anchored on the method's name so the report lands on the signature, which
    * is the thing that would change. Effects inside a nested `def` belong to
    * that nested def, which is a `Defn.Def` of its own and reports separately.
    */
  def findings(
      tree: Tree,
      facts: SideEffectFacts,
      effectNames: Set[String]
  ): List[IdiomFinding] =
    tree.collect {
      case defn: Defn.Def
          if defn.decltpe.isDefined && !resultIsEffect(defn, effectNames) =>
        val effects = unsuspendedEffects(defn.body, facts)
          .filterNot(effect => enclosedByNestedDef(effect, defn))
        Option.when(effects.nonEmpty)(
          IdiomFinding(defn.name, unsuspendedEffectMessage(effects.size))
        )
    }.flatten

  /** Whether the effect belongs to a `def` nested inside this one. */
  private def enclosedByNestedDef(effect: Term, defn: Defn.Def): Boolean = {
    def loop(tree: Tree): Boolean =
      tree.parent match {
        case Some(parent) if parent eq defn => false
        case Some(parent: Defn.Def)         => true
        case Some(parent)                   => loop(parent)
        case None                           => false
      }
    loop(effect)
  }

  /** `Sync[F].pure(<effect>)` -> `Sync[F].delay(<effect>)`.
    *
    * `pure` takes its argument by value, so the effect has already run by the
    * time there is an `F` to hold it. Every subsequent run replays the first
    * result. This is the one shape here where the rewrite fixes behaviour.
    */
  private def eagerPure(
      term: Term,
      facts: SideEffectFacts,
      catsFacts: CatsFacts
  ): Option[IdiomRewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(receiver, name) if name.value == "pure" =>
            for {
              _ <- catsFacts.typeclassObject(receiver).filter(Suspendable)
              argument <- singleArg(apply.argClause.values)
              if unsuspendedEffects(argument, facts).nonEmpty
            } yield IdiomRewrite(
              term,
              s"${receiver.syntax}.delay(${argument.syntax})"
            )
          case _ =>
            None
        }
      case _ =>
        None
    }

  /** Only the entry points that *have* a `delay`. `Applicative[F].pure` around
    * an effect is the same defect, but `Applicative` cannot suspend, so there
    * is nothing to rewrite it to.
    */
  private val Suspendable: Set[String] = Set(
    // `Sync` and `Async` are declared in `cats.effect.kernel` and reach user
    // code as aliases in the `cats.effect` package object, so `import
    // cats.effect.Sync` emits `cats/effect/package.Sync.` -- the same shape
    // `CatsFacts.Typeclasses` records for `MonadThrow`. All three spellings
    // name the same entry point; which one appears depends on how it was
    // imported, so a table with only one of them silently misses the others.
    "cats/effect/package.Sync.",
    "cats/effect/package.Async.",
    "cats/effect/kernel/Sync.",
    "cats/effect/kernel/Async.",
    "cats/effect/IO.",
    "cats/effect/SyncIO."
  )

  /** Every call in `tree` that touches the world without being suspended. */
  private def unsuspendedEffects(
      tree: Tree,
      facts: SideEffectFacts
  ): List[Term] =
    tree.collect {
      case term: Term
          if isEffectCall(term, facts) && !isSuspended(term) &&
            !isEffectCall(parentTerm(term).orNull, facts) =>
        term
    }

  /** Whether the term resolves to something that touches the world.
    *
    * Reported at the outermost term of a call so `Files.writeString(p, s)` is
    * one finding rather than one per resolved sub-select.
    */
  private def isEffectCall(term: Term, facts: SideEffectFacts): Boolean =
    term != null &&
      facts.symbolsAt(term).exists(SideEffectFacts.isUnsuspended)

  private def parentTerm(tree: Tree): Option[Term] =
    tree.parent.collect { case term: Term => term }

  /** Whether an enclosing call already suspends this one.
    *
    * `IO.delay { println(…) }`, `Sync[F].blocking { … }`, and the `IO { … }`
    * spelling of the same all put the effect behind a value, which is the whole
    * point. A finding inside one of those would be reporting the fix.
    */
  private def isSuspended(tree: Tree): Boolean =
    tree.parent.exists {
      case parent: Term.Apply if suspends(parent.fun) => true
      case parent                                     => isSuspended(parent)
    }

  private def suspends(fun: Term): Boolean =
    fun match {
      case Term.Select(_, Term.Name(name)) => Suspenders.contains(name)
      case Term.Name(name)                 => EffectEntryPoints.contains(name)
      case applyType: Term.ApplyType       => suspends(applyType.fun)
      case _                               => false
    }

  private val Suspenders: Set[String] =
    Set(
      "delay",
      "blocking",
      "interruptible",
      "interruptibleMany",
      "suspend",
      "defer",
      "apply"
    )

  /** `IO { … }` and `SyncIO { … }` suspend by applying the companion. */
  private val EffectEntryPoints: Set[String] = Set("IO", "SyncIO")

  /** Whether the declared result already carries the effect.
    *
    * A method returning `F[A]`, `IO[A]`, `Resource[F, A]` or `Stream[F, A]` is
    * already honest, whatever its body does -- and a body that suspends inside
    * one of those is the shape this rule is asking for.
    *
    * Read from the *written* type rather than from the inferred one, because
    * what is under discussion is what the signature says.
    *
    * A def with no declared result is skipped entirely rather than assumed
    * non-effectful. `def functions[F[_]: Sync](pr: Registry[F]) = (gauge, ref)`
    * infers a tuple of `F`s and is perfectly honest; reporting it would be
    * guessing at a signature the author did not write.
    */
  private def resultIsEffect(
      defn: Defn.Def,
      effectNames: Set[String]
  ): Boolean = {
    val heads = effectNames ++ higherKindedNames(defn)
    defn.decltpe.exists {
      case applied: Type.Apply => headName(applied.tpe).exists(heads)
      case Type.Name(name)     => heads.contains(name)
      case _                   => false
    }
  }

  private def headName(tpe: Type): Option[String] =
    tpe match {
      case Type.Name(name)                 => Some(name)
      case Type.Select(_, Type.Name(name)) => Some(name)
      case applied: Type.Apply             => headName(applied.tpe)
      case _                               => None
    }

  /** The `F` of every `F[_]` in scope: the def's own type parameters and those
    * of every enclosing class, trait or method.
    *
    * Without this a `def run[F[_]: Sync](…): F[Unit]` would be reported, since
    * `F` is in no fixed table of effect names -- and abstracting over the
    * effect is exactly what the rule is asking for.
    */
  private def higherKindedNames(defn: Defn.Def): Set[String] =
    (defn :: ancestors(defn))
      .flatMap(typeParameters)
      .filter(_.tparamClause.values.nonEmpty)
      .map(_.name.value)
      .toSet

  private def ancestors(tree: Tree): List[Tree] =
    tree.parent.toList.flatMap(parent => parent :: ancestors(parent))

  private def typeParameters(tree: Tree): List[Type.Param] =
    tree match {
      case defn: Defn.Def =>
        defn.paramClauseGroups.flatMap(_.tparamClause.values)
      case defn: Defn.Class => defn.tparamClause.values
      case defn: Defn.Trait => defn.tparamClause.values
      case defn: Defn.Type  => defn.tparamClause.values
      case _                => Nil
    }
}
