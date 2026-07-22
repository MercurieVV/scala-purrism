package fix.arrow

import scala.meta._

import scalafix.v1._

/** Decides whether a term is a `Kleisli`, by symbol, through type aliases.
  *
  * This replaces `PreferKleisli.collectKleisliNames` for `PreferArrow`.
  * `collectKleisliNames` is a `Set[String]` populated from declarations whose
  * *written* type renders exactly `"Kleisli"` with three arguments, which
  * misses every alias (`-->[F, A, B]`), every fully-qualified spelling
  * (`cats.data.Kleisli[F, A, B]`), and every inferred `val k = Kleisli(...)`.
  *
  * The name-based approach is not merely incomplete, it is unsound: in the
  * reference corpus `-->` is *both* a Kleisli alias (`main.scala`) and an
  * abstract type parameter (`ProgramArrows[-->[_, _]]` in
  * `ArrowLogging.scala`). Matching the token would fuse the two. Per
  * `docs/RULES.md`, identifier names are not identity, so every question here
  * is answered from SemanticDB.
  */
object KleisliType {

  private val KleisliSymbol = "cats/data/Kleisli#"
  private val KleisliObjectSymbol = Symbol("cats/data/Kleisli.")
  private val KleisliApplySymbol = Symbol("cats/data/Kleisli.apply().")

  /** Guards against a cyclic alias chain, which would otherwise not terminate.
    * The limit is far above any real alias depth; hitting it means "give up",
    * not "yes".
    */
  private val MaxDealiasDepth = 16

  def isKleisli(term: Term)(implicit doc: SemanticDocument): Boolean =
    semanticType(term).exists(resolvesToKleisli(_, 0))

  /** The type of a term, as far as a declaration's signature reveals it.
    *
    * For a `MethodSignature` this is the *return* type, which is what a fully
    * applied call evaluates to. Callers therefore ask about the callee
    * expression -- `loadUser` in `loadUser.run(x)` -- and never about the
    * enclosing application node.
    */
  private def semanticType(
      term: Term
  )(implicit doc: SemanticDocument): Option[SemanticType] =
    term.symbol.info.map(_.signature).flatMap {
      case ValueSignature(tpe)        => Some(tpe)
      case MethodSignature(_, _, tpe) => Some(tpe)
      case _                          => None
    }

  private def resolvesToKleisli(
      tpe: SemanticType,
      depth: Int
  )(implicit doc: SemanticDocument): Boolean =
    if (depth > MaxDealiasDepth) false
    else
      tpe match {
        case TypeRef(_, symbol, _) if symbol.value == KleisliSymbol =>
          true
        case TypeRef(_, symbol, _) =>
          dealias(symbol).exists(resolvesToKleisli(_, depth + 1))
        // A *nullary* `def` -- `def changedPlan[F[_]: Sync]: -->[F, A, B]`,
        // the dominant corpus shape -- is emitted as a `ValueSignature`
        // wrapping a `ByNameType`, not as a `MethodSignature`. Omitting this
        // case silently rejected every such declaration.
        case ByNameType(result) => resolvesToKleisli(result, depth + 1)
        // `type Flow[F[_]] = [A, B] =>> Kleisli[F, A, B]` -- the alias body is
        // a type lambda, so the Kleisli sits one level in.
        case LambdaType(_, result)    => resolvesToKleisli(result, depth + 1)
        case UniversalType(_, result) => resolvesToKleisli(result, depth + 1)
        case AnnotatedType(_, result) => resolvesToKleisli(result, depth + 1)
        case _                        => false
      }

  /** The right-hand side of a type alias, or `None` for an abstract type.
    *
    * SemanticDB gives both an alias and an abstract type a `TypeSignature`. The
    * discriminator is that an alias has its right-hand side as *both* bounds,
    * whereas an abstract type parameter has genuine (usually `Nothing`/`Any`)
    * bounds. That distinction is what keeps `ProgramArrows[-->[_, _]]` from
    * being mistaken for the `-->` Kleisli alias.
    *
    * The bounds are compared by rendered structure rather than by `==`: for a
    * higher-kinded alias (`type Flow[F[_]] = [A, B] =>> Kleisli[F, A, B]`) the
    * two bounds are distinct `LambdaType` instances -- their bound-parameter
    * symbols differ -- so reference/`case class` equality reports them unequal
    * even though they denote the same type.
    */
  private def dealias(
      symbol: Symbol
  )(implicit doc: SemanticDocument): Option[SemanticType] =
    symbol.info.map(_.signature).flatMap {
      case TypeSignature(_, lowerBound, upperBound)
          if sameType(lowerBound, upperBound) =>
        Some(upperBound)
      case _ =>
        None
    }

  private def sameType(a: SemanticType, b: SemanticType): Boolean =
    a.toString == b.toString

  /** Whether this application is a `Kleisli` constructor call -- the
    * `Kleisli { x => ... }`, `Kleisli.apply { x => ... }` and
    * `Kleisli[F, A, B] { x => ... }` forms alike.
    *
    * Nothing in the codebase recognised the bare form before: the existing
    * `PreferKleisli.kleisliApplyFunction` gates on a `Term.Select` named
    * `apply`, but `Kleisli { x => ... }` parses as
    * `Term.Apply(Term.Name("Kleisli"), ...)` with no select at all. Resolving
    * the symbol covers all three spellings at once.
    */
  def isKleisliApply(
      applyTerm: Term.Apply
  )(implicit doc: SemanticDocument): Boolean = {
    // The bare and ascribed spellings resolve to the *companion object*
    // (`cats/data/Kleisli.`), since the `apply` is inserted by the compiler and
    // has no syntax to carry the symbol. Only the explicit `Kleisli.apply {...}`
    // spelling resolves to `cats/data/Kleisli.apply().`. Accept both.
    val symbol = applyTerm.fun.symbol
    symbol == KleisliObjectSymbol || symbol == KleisliApplySymbol
  }
}
