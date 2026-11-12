package fix.weakening

import scala.meta.*

import fix.hkt.CatsIndex
import scalafix.v1.*

/** A context bound `tparam: bound` on owner `owner` (a def, class or trait).
  *
  * `boundSymbol` is the typeclass the bound denotes: the bound's own symbol, or
  * the target of the alias it is written through (`MonadThrow` denotes
  * `MonadError`), in which case `boundFixedArgs` holds the alias's fixed
  * arguments (`java/lang/Throwable#`); `Nil` otherwise.
  */
final case class BoundSite(
    owner: Tree,
    tparam: Type.Param,
    bound: Type,
    boundSymbol: Symbol,
    boundFixedArgs: List[String]
)

/** Which typeclasses an owner needs from one of its effect type parameters.
  *
  * Read from the compiler rather than from method names:
  *
  *   - direct calls: each `Term.Name` resolving through the index to a
  *     capability declared by the bound or one of its ancestors; and
  *   - every synthetic application the compiler handed the type parameter's
  *     evidence to -- `toFunctorOps(fa)(using ev)`, `xs.traverse(f)(using ev)`.
  *     Each must be explained by an indexed capability, a `toXOps` syntax
  *     conversion naming its typeclass, a local helper's weakened bound, the
  *     library `evidence` table or the SemanticDB signature of a local method.
  *     One that cannot be explained makes the answer `None`: keep the bound.
  *
  * Scala 3 records one evidence application per span. When a method call's span
  * also carries a syntax conversion (`x <- 1.pure[F]` converts `1.pure[F]` with
  * `toFlatMapOps`), the single recorded `(using ev)` may belong to either, so
  * both must be explained and both contribute.
  *
  * Evidence named in source (`[F[_]: Sync as S]`, or any value of type `TC[F]`)
  * may only be the qualifier of a call resolving to a capability (`S.map`); any
  * other reference (`foo(S)`, `given X = S`) is unexplained.
  *
  * Evidence is recognised by the type parameter's *symbol*, never its name: an
  * inner `def h[F[_]: Sync]` inside `class S[F[_]: Sync]` declares a different
  * `F`, and neither its evidence nor its body's direct calls count for the
  * outer bound.
  *
  * Typeclasses outside the bound's ancestry constrain another type
  * (`xs.traverse`'s `Traverse` constrains `List`) and are ignored.
  */
object Requirements {

  private val SyntaxConversion = "to([A-Za-z]+)Ops".r

  /** Every unary type parameter's context bound that resolves, directly or
    * through an alias, to a typeclass the index knows.
    */
  def sites(tree: Tree, index: CatsIndex)(using
      SemanticDocument
  ): List[BoundSite] =
    tree.collect {
      case defn: Defn.Def =>
        sitesOf(
          defn,
          defn.paramClauseGroups.flatMap(_.tparamClause.values),
          index
        )
      case cls: Defn.Class => sitesOf(cls, cls.tparamClause.values, index)
      case tr: Defn.Trait  => sitesOf(tr, tr.tparamClause.values, index)
    }.flatten

  private def sitesOf(
      owner: Tree,
      tparams: List[Type.Param],
      index: CatsIndex
  )(using SemanticDocument): List[BoundSite] =
    tparams
      .filter(_.tparamClause.values.size == 1)
      .flatMap(tparam =>
        tparam.bounds.context
          .map(unaliased)
          .flatMap(bound =>
            resolveBound(bound.symbol, index).map { case (symbol, fixedArgs) =>
              BoundSite(owner, tparam, bound, symbol, fixedArgs)
            }
          )
      )

  /** `Sync as S` names its evidence; the bound itself is `Sync`. */
  private def unaliased(bound: Type): Type =
    bound match {
      case Type.BoundsAlias(_, inner) => inner
      case other                      => other
    }

  private def resolveBound(
      symbol: Symbol,
      index: CatsIndex
  ): Option[(Symbol, List[String])] =
    if (index.typeclasses.contains(symbol)) Some(symbol -> Nil)
    else
      index
        .aliasTarget(symbol)
        .filter(alias => index.typeclasses.contains(alias.target))
        .map(alias => alias.target -> alias.fixedArgs)

  /** The typeclasses `site.owner` needs from `site.tparam`, all of them the
    * bound or its ancestors; `None` when some evidence application cannot be
    * explained.
    *
    * @param helpers
    *   for a local method being weakened, the post-decision bounds of all its
    *   type parameters that receive this parameter's evidence; they replace
    *   that method's signature evidence and all join the result.
    */
  def collect(
      site: BoundSite,
      index: CatsIndex,
      helpers: Symbol => Option[Set[Symbol]]
  )(using doc: SemanticDocument): Option[Set[Symbol]] = {
    val effect = site.tparam.name.symbol
    val inLineage = (typeclass: Symbol) =>
      typeclass == site.boundSymbol ||
        index.isAncestor(typeclass, site.boundSymbol)

    val shadowed = shadowingScopes(site)
    val direct =
      site.owner
        .collect { case name: Term.Name => name }
        .filterNot(name => shadowed.exists(within(name.pos, _)))
        .flatMap(name => resolvedTypeclass(name.symbol, index).toList)

    val explicitEvidenceExplained =
      site.owner
        .collect { case name: Term.Name => name }
        .filterNot(name => within(name.pos, site.tparam.pos))
        .filterNot(isDefinitionName)
        .filter(name => name.symbol.info.exists(isEvidence(_, effect, index)))
        .forall(name =>
          name.parent.exists {
            case select: Term.Select if select.qual eq name =>
              resolvedTypeclass(select.name.symbol, index).exists(inLineage)
            case _ => false
          }
        )

    val synthetics =
      doc.synthetics.toList.filter(synthetic =>
        anchoredIn(synthetic, site.owner)
      )
    val conversions = conversionsByPosition(synthetics)

    val explained =
      synthetics
        .flatMap(evidenceCalls(_, effect, index))
        .map { case (function, original) =>
          val conversion = original.flatMap(conversions.get)
          val targets =
            conversion match {
              case Some(converted) if isMethod(function) =>
                List(function, converted)
              case Some(converted) => List(converted)
              case None            => List(function)
            }
          val explanations =
            targets.map(explain(_, index, helpers).filter(inLineage))
          Option.when(explanations.forall(_.nonEmpty))(explanations.flatten)
        }

    Option
      .when(explicitEvidenceExplained && explained.forall(_.isDefined))(
        direct ++ explained.flatten.flatten
      )
      .map(_.filter(inLineage).toSet)
  }

  /** The name a definition introduces, not a reference to a value. */
  private def isDefinitionName(name: Term.Name): Boolean =
    name.parent.exists {
      case _: Pat.Var | _: Term.Param => true
      case defn: Defn.Def             => defn.name eq name
      case defn: Defn.Given           => defn.name eq name
      case _                          => false
    }

  /** A method's own `using` clause, as opposed to the evidence of a conversion
    * applied to a value: `xs.traverse(f)(using ev)` belongs to `traverse` even
    * when `toFunctorOps(xs.traverse(f))` sits on the same span.
    *
    * Decided from the symbol's SemanticDB signature when the document has it
    * (local defs are `localN`, with no `).` suffix); the suffix is only the
    * fallback for TASTy-only globals without `info`.
    */
  def isMethod(symbol: Symbol)(using SemanticDocument): Boolean =
    symbol.info match {
      case Some(info) =>
        info.signature match {
          case MethodSignature(_, parameterLists, _) => parameterLists.nonEmpty
          case _                                     => false
        }
      case None => symbol.value.endsWith(").")
    }

  /** Nested definitions that declare a type parameter of the same name: the
    * site's parameter is not nameable there, so their direct calls are for
    * another type.
    */
  private def shadowingScopes(site: BoundSite): List[Position] = {
    val name = site.tparam.name.value
    site.owner
      .collect {
        case defn: Defn.Def if defn ne site.owner =>
          defn -> defn.paramClauseGroups.flatMap(_.tparamClause.values)
        case cls: Defn.Class if cls ne site.owner =>
          cls -> cls.tparamClause.values
        case tr: Defn.Trait if tr ne site.owner =>
          tr -> tr.tparamClause.values
      }
      .collect {
        case (defn, tparams) if tparams.exists(_.name.value == name) =>
          defn.pos
      }
  }

  private def within(inner: Position, outer: Position): Boolean =
    outer.start <= inner.start && inner.end <= outer.end

  private def explain(
      function: Symbol,
      index: CatsIndex,
      helpers: Symbol => Option[Set[Symbol]]
  )(using SemanticDocument): List[Symbol] =
    helpers(function) match {
      case Some(weakened) => weakened.toList
      case None =>
        resolvedTypeclass(function, index).toList ++
          conversion(function, index).toList ++
          tableEvidence(function, index) ++
          signatureEvidence(function, index)
    }

  /** The `evidence` table's typeclasses for a library method. A syntax method
    * (`cats/Traverse.Ops#traverse().`) has none of its own; the capability it
    * forwards to (`cats/Traverse#traverse().`) does.
    */
  private def tableEvidence(function: Symbol, index: CatsIndex): List[Symbol] =
    (function :: index
      .resolveSyntax(function)
      .toList
      .flatMap(capability =>
        List(capability.method, capability.owner)
      )).distinct
      .flatMap(index.evidenceOf)
      .flatMap(normalise(_, index))

  /** The typeclass declaring the capability `method` resolves to. */
  private def resolvedTypeclass(
      method: Symbol,
      index: CatsIndex
  ): Option[Symbol] =
    index
      .resolveSyntax(method)
      .map(_.owner)
      .orElse(index.primitiveOwner(method))
      .flatMap(typeclassOf(_, index))

  private def typeclassOf(method: Symbol, index: CatsIndex): Option[Symbol] = {
    val value = method.value
    val separator = value.indexOf('#')
    Option
      .when(separator > 0)(Symbol(value.substring(0, separator + 1)))
      .filter(index.typeclasses.contains)
  }

  /** `toFunctorOps` names `Functor`: the one indexed typeclass of that name. */
  private def conversion(function: Symbol, index: CatsIndex): Option[Symbol] =
    function.displayName match {
      case SyntaxConversion(name) =>
        index.typeclasses.keys.filter(_.displayName == name).toList match {
          case single :: Nil => Some(single)
          case _             => None
        }
      case _ => None
    }

  private def normalise(typeclass: Symbol, index: CatsIndex): Option[Symbol] =
    resolveBound(typeclass, index).map(_._1)

  /** Typeclass evidence a local method's signature declares. `info` is absent
    * for TASTy-only library symbols, which the `evidence` table covers.
    */
  private def signatureEvidence(function: Symbol, index: CatsIndex)(using
      SemanticDocument
  ): List[Symbol] =
    function.info.toList.flatMap(_.signature match {
      case MethodSignature(_, parameterLists, _) =>
        parameterLists.flatten.flatMap(_.signature match {
          case ValueSignature(TypeRef(_, typeclass, _ :: _)) =>
            normalise(typeclass, index).toList
          case _ => Nil
        })
      case _ => Nil
    })

  /** Implicit conversions applied to source expressions, keyed by the span of
    * the converted expression.
    *
    * Scala 3 records a syntax conversion and the evidence it takes as two
    * synthetics on the same span -- `toFunctorOps(fa)` and `fa(using ev)` -- so
    * the evidence application names `fa`, not the conversion that wanted the
    * evidence.
    */
  private def conversionsByPosition(synthetics: List[SemanticTree])(using
      SemanticDocument
  ): Map[(Int, Int), Symbol] =
    synthetics.flatMap {
      case ApplyTree(function, List(OriginalTree(original))) =>
        List((original.pos.start, original.pos.end) -> functionSymbol(function))
      case _ => Nil
    }.toMap

  /** The function of every synthetic application handed `effect`'s evidence --
    * `Symbol.None` when it cannot be named -- with the span of the source
    * expression when that function is one.
    */
  private def evidenceCalls(
      tree: SemanticTree,
      effect: Symbol,
      index: CatsIndex
  )(using
      SemanticDocument
  ): List[(Symbol, Option[(Int, Int)])] =
    tree match {
      case ApplyTree(function, arguments) =>
        val here =
          if (arguments.exists(passesEvidence(_, effect, index)))
            List(functionSymbol(function) -> originalSpan(function))
          else Nil
        here ++ (function :: arguments).flatMap(
          evidenceCalls(_, effect, index)
        )
      case SelectTree(qualifier, _)   => evidenceCalls(qualifier, effect, index)
      case TypeApplyTree(function, _) => evidenceCalls(function, effect, index)
      case FunctionTree(_, body)      => evidenceCalls(body, effect, index)
      case MacroExpansionTree(before, _) => evidenceCalls(before, effect, index)
      case _                             => Nil
    }

  private def passesEvidence(
      tree: SemanticTree,
      effect: Symbol,
      index: CatsIndex
  ): Boolean =
    tree match {
      case IdTree(info) => isEvidence(info, effect, index)
      case ApplyTree(function, arguments) =>
        (function :: arguments).exists(passesEvidence(_, effect, index))
      case SelectTree(qualifier, id) =>
        passesEvidence(qualifier, effect, index) ||
        passesEvidence(id, effect, index)
      case TypeApplyTree(function, _) =>
        passesEvidence(function, effect, index)
      case _ => false
    }

  /** A value of shape `TC[F, ...]` for exactly this type parameter `F` -- the
    * context-bound evidence or anything derived from it.
    */
  private def isEvidence(
      info: SymbolInformation,
      effect: Symbol,
      index: CatsIndex
  ): Boolean =
    info.signature match {
      case ValueSignature(tpe)            => appliedTo(tpe, effect, index)
      case MethodSignature(Nil, Nil, tpe) => appliedTo(tpe, effect, index)
      case _                              => false
    }

  /** Only a typeclass the index knows (or an alias of one) is evidence; a
    * tagless algebra `Profiles[F]` is an ordinary value.
    */
  private def appliedTo(
      tpe: SemanticType,
      effect: Symbol,
      index: CatsIndex
  ): Boolean =
    tpe match {
      case TypeRef(_, head, TypeRef(_, first, Nil) :: _) =>
        first == effect &&
        (index.typeclasses.contains(head) || index.aliasTarget(head).isDefined)
      case _ => false
    }

  private def functionSymbol(tree: SemanticTree)(using
      SemanticDocument
  ): Symbol =
    tree match {
      case IdTree(info)                => info.symbol
      case SelectTree(_, IdTree(info)) => info.symbol
      case TypeApplyTree(function, _)  => functionSymbol(function)
      case ApplyTree(function, _)      => functionSymbol(function)
      case OriginalTree(term)          => termSymbol(term)
      case OriginalSubTree(term)       => termSymbol(term)
      case _                           => Symbol.None
    }

  private def originalSpan(tree: SemanticTree): Option[(Int, Int)] =
    tree match {
      case OriginalTree(original) =>
        Some((original.pos.start, original.pos.end))
      case OriginalSubTree(original) =>
        Some((original.pos.start, original.pos.end))
      case _ => None
    }

  private def termSymbol(tree: Tree)(using SemanticDocument): Symbol =
    tree match {
      case apply: Term.Apply         => termSymbol(apply.fun)
      case applyType: Term.ApplyType => termSymbol(applyType.fun)
      case select: Term.Select       => select.name.symbol
      case other                     => other.symbol
    }

  private def anchoredIn(synthetic: SemanticTree, owner: Tree): Boolean =
    anchor(synthetic).exists(within(_, owner.pos))

  private def anchor(tree: SemanticTree): Option[Position] =
    tree match {
      case OriginalTree(original)    => Some(original.pos)
      case OriginalSubTree(original) => Some(original.pos)
      case ApplyTree(function, arguments) =>
        (function :: arguments).iterator.flatMap(anchor).nextOption()
      case SelectTree(qualifier, _)      => anchor(qualifier)
      case TypeApplyTree(function, _)    => anchor(function)
      case FunctionTree(_, body)         => anchor(body)
      case MacroExpansionTree(before, _) => anchor(before)
      case _                             => None
    }
}
