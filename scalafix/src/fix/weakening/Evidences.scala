package fix.weakening

import scala.meta.*

import fix.hkt.CatsIndex
import scalafix.v1.*

/** Which typeclass instances a scope holds for a type parameter.
  *
  * Weakening one bound changes which instances implicit search can choose
  * between. Two candidates for the same typeclass where neither is more
  * specific make the search ambiguous, and the code stops compiling. Both
  * checks here keep the bound in that case.
  */
private[fix] object Evidences {

  /** A type parameter holding more than one evidence: several context bounds
    * (`F[_]: Temporal: Sync`), or a bound next to an explicit `using`/
    * `implicit` parameter of its owner that mentions it. Solving each bound
    * alone could emit `F[_]: Functor: Functor`, or a `Functor` next to a
    * `Temporal[F]` the bound used to outrank, so all such bounds are kept.
    */
  def several(site: BoundSite)(using SemanticDocument): Boolean =
    site.tparam.bounds.context.size > 1 ||
      implicitParams(site.owner).exists(
        _.decltpe.exists(mentions(_, site.tparam.name.symbol))
      )

  /** Some call of `callees` in this document runs where its type argument has
    * two or more in-scope evidences that descend from (or equal) `target`, so
    * `target` would be ambiguous there (ruling R18). Callers in other files
    * cannot be seen. That is a known risk.
    *
    * A call is a synthetic evidence application anchored on a span that
    * contains a reference to a callee. Its type arguments are the type
    * parameters the passed evidence is applied to.
    */
  def ambiguousAtCallSite(
      callees: Set[Symbol],
      target: Symbol,
      index: CatsIndex
  )(using doc: SemanticDocument): Boolean = {
    val references = doc.tree.collect {
      case name: Name if callees.contains(name.symbol) && !isDefinition(name) =>
        name.pos
    }
    references.nonEmpty &&
    doc.synthetics.toList.flatMap(calls).exists { case (span, effects) =>
      references.exists(within(_, span)) &&
      effects.exists(effect =>
        inScope(effect, span, index).count(descends(_, target, index)) >= 2
      )
    }
  }

  private def isDefinition(name: Name): Boolean =
    name.parent.exists {
      case defn: Defn.Def  => defn.name eq name
      case cls: Defn.Class => cls.name eq name
      case tr: Defn.Trait  => tr.name eq name
      case _: Ctor.Primary => true
      case _               => false
    }

  /** Every evidence application within `tree`: its anchor span and the type
    * parameters the evidence passed is applied to.
    */
  private def calls(tree: SemanticTree): List[(Position, Set[Symbol])] =
    tree match {
      case ApplyTree(function, arguments) =>
        val effects = arguments.flatMap(effectsOf).toSet
        val here =
          if (effects.isEmpty) Nil
          else anchor(function).map(_ -> effects).toList
        here ++ (function :: arguments).flatMap(calls)
      case SelectTree(qualifier, _)      => calls(qualifier)
      case TypeApplyTree(function, _)    => calls(function)
      case FunctionTree(_, body)         => calls(body)
      case MacroExpansionTree(before, _) => calls(before)
      case _                             => Nil
    }

  private def effectsOf(tree: SemanticTree): List[Symbol] =
    tree match {
      case IdTree(info) => appliedEffect(info.signature).toList
      case ApplyTree(function, arguments) =>
        (function :: arguments).flatMap(effectsOf)
      case SelectTree(qualifier, id)  => effectsOf(qualifier) ++ effectsOf(id)
      case TypeApplyTree(function, _) => effectsOf(function)
      case _                          => Nil
    }

  /** `F` for a value of shape `TC[F, ...]`. */
  private def appliedEffect(signature: Signature): Option[Symbol] =
    signature match {
      case ValueSignature(TypeRef(_, _, TypeRef(_, effect, Nil) :: _)) =>
        Some(effect)
      case MethodSignature(
            Nil,
            Nil,
            TypeRef(_, _, TypeRef(_, effect, Nil) :: _)
          ) =>
        Some(effect)
      case _ => None
    }

  /** The typeclasses of every evidence for `effect` visible at `at`. `None`
    * stands for a context bound the index does not know, which may descend from
    * anything. Implicit values are counted only when their typeclass is
    * indexed, since a `using` algebra such as `Profiles[F]` is no instance.
    */
  private def inScope(effect: Symbol, at: Position, index: CatsIndex)(using
      doc: SemanticDocument
  ): List[Option[Symbol]] = {
    val bounds = doc.tree.collect {
      case tparam: Type.Param if tparam.name.symbol == effect =>
        tparam.bounds.context.map(bound =>
          resolve(unaliased(bound).symbol, index)
        )
    }.flatten
    val values = doc.tree.collect {
      case param: Term.Param
          if isImplicit(param) && paramScope(param).exists(within(at, _)) =>
        param.decltpe.toList
      case alias: Defn.GivenAlias
          if alias.parent.exists(p => within(at, p.pos)) =>
        List(alias.decltpe)
      case value: Defn.Val
          if hasImplicitMod(value.mods) &&
            value.parent.exists(p => within(at, p.pos)) =>
        value.decltpe.toList
    }.flatten
    bounds ++ values
      .flatMap(head(_, effect))
      .flatMap(resolve(_, index))
      .map(Some(_))
  }

  /** `TC` for a written type `TC[F, ...]` whose first argument is `effect`. */
  private def head(tpe: Type, effect: Symbol)(using
      SemanticDocument
  ): Option[Symbol] =
    tpe match {
      case applied: Type.Apply =>
        applied.argClause.values.headOption
          .filter(_.symbol == effect)
          .map(_ => applied.tpe.symbol)
      case _ => None
    }

  private def descends(
      typeclass: Option[Symbol],
      target: Symbol,
      index: CatsIndex
  ): Boolean =
    typeclass.forall(tc => tc == target || index.isAncestor(target, tc))

  private def resolve(symbol: Symbol, index: CatsIndex): Option[Symbol] =
    if (index.typeclasses.contains(symbol)) Some(symbol)
    else
      index
        .aliasTarget(symbol)
        .map(_.target)
        .filter(index.typeclasses.contains)

  private def unaliased(bound: Type): Type =
    bound match {
      case Type.BoundsAlias(_, inner) => inner
      case other                      => other
    }

  private def implicitParams(owner: Tree): List[Term.Param] =
    (owner match {
      case defn: Defn.Def  => defn.paramClauseGroups.flatMap(_.paramClauses)
      case cls: Defn.Class => cls.ctor.paramClauses.toList
      case tr: Defn.Trait  => tr.ctor.paramClauses.toList
      case _               => Nil
    }).flatMap(_.values).filter(isImplicit)

  private def isImplicit(param: Term.Param): Boolean =
    hasImplicitMod(param.mods) ||
      param.parent.exists {
        case clause: Term.ParamClause => clause.mod.exists(isImplicitMod)
        case _                        => false
      }

  private def hasImplicitMod(mods: List[Mod]): Boolean =
    mods.exists(isImplicitMod)

  private def isImplicitMod(mod: Mod): Boolean =
    mod match {
      case _: Mod.Using | _: Mod.Implicit => true
      case _                              => false
    }

  /** The definition a parameter belongs to: the nearest enclosing def, class,
    * trait, given or lambda (the parameter clause and its clause group sit in
    * between).
    */
  private def paramScope(param: Term.Param): Option[Position] = {
    def up(tree: Tree): Option[Position] =
      tree match {
        case _: Defn | _: Term.FunctionTerm => Some(tree.pos)
        case other                          => other.parent.flatMap(up)
      }
    param.parent.flatMap(up)
  }

  private def mentions(tpe: Type, effect: Symbol)(using
      SemanticDocument
  ): Boolean =
    tpe.collect {
      case name: Type.Name if name.symbol == effect => name
    }.nonEmpty

  private def within(inner: Position, outer: Position): Boolean =
    outer.start <= inner.start && inner.end <= outer.end

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
