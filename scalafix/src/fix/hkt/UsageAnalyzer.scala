package fix.hkt

import scala.meta._
import scala.meta.inputs.Position

import scalafix.v1.MethodSignature
import scalafix.v1.SemanticDocument
import scalafix.v1.SemanticType
import scalafix.v1.Symbol
import scalafix.v1.TypeRef
import scalafix.v1.ValueSignature
import scalafix.v1.XtensionTreeScalafix

final case class RequiredOp(
    method: Symbol,
    position: Position,
    kind: KindShape
)

sealed trait DeclineReason {
  def message: String
}

object DeclineReason {
  final case class ConcreteConstructorMatch(what: String)
      extends DeclineReason {
    def message: String =
      s"concrete constructor pattern cannot be abstracted: $what"
  }
  final case class OrderOrIndexSpecific(what: String) extends DeclineReason {
    def message: String =
      s"order- or index-specific operation cannot be abstracted: $what"
  }
  final case class UnsupportedKind(shape: KindShape) extends DeclineReason {
    def message: String =
      s"unsupported type-constructor kind: ${KindShape.render(shape)}"
  }
  final case class PublicBoundary(defName: String) extends DeclineReason {
    def message: String = s"public API boundary cannot be widened: $defName"
  }
  final case class AmbiguousCapability(candidates: List[Symbol])
      extends DeclineReason {
    def message: String =
      s"operation has unrelated capability roots: ${candidates.map(_.value).mkString(", ")}"
  }
  final case class NoCapability(method: Symbol) extends DeclineReason {
    def message: String =
      s"no indexed capability for operation: ${method.value}"
  }
  final case class UnsafeBody(what: String) extends DeclineReason {
    def message: String = s"unsafe body cannot be abstracted: $what"
  }
  final case class NameConflict(tried: List[String]) extends DeclineReason {
    def message: String =
      s"no conflict-free type parameter name among: ${tried.mkString(", ")}"
  }
  final case class TooManyConstraints(candidate: List[Symbol], max: Int)
      extends DeclineReason {
    def message: String =
      s"candidate requires ${candidate.size} constraints, exceeding maximum $max: " +
        candidate.map(_.value).mkString(", ")
  }
  case object MissingEvidence extends DeclineReason {
    def message: String =
      "semantic evidence required for usage analysis is missing"
  }
}

sealed trait UsageResult

object UsageResult {
  final case class Abstractable(
      defn: scala.meta.Defn.Def,
      target: scala.meta.Type,
      constructor: Symbol,
      elementType: scala.meta.Type,
      ops: List[RequiredOp]
  ) extends UsageResult

  final case class Declined(position: Position, reason: DeclineReason)
      extends UsageResult
}

object UsageAnalyzer {
  private final case class Target(
      tpe: Type,
      constructor: Symbol,
      elementType: Type,
      kind: KindShape
  )

  private final case class Call(
      receiver: Term,
      method: Symbol,
      position: Position,
      span: Position
  )

  private final case class Resolved(
      owner: Symbol,
      providers: List[Symbol],
      kind: KindShape
  )

  private type SyntheticEvidence = fix.SemanticSupport.SyntheticEvidence

  private sealed trait Lookup
  private final case class Found(values: List[Resolved]) extends Lookup
  private final case class Rejected(reason: DeclineReason) extends Lookup
  private case object NotFound extends Lookup

  private val constructorAliases: Map[Symbol, Symbol] = Map(
    Symbol("scala/package.List#") -> Symbol("scala/collection/immutable/List#"),
    Symbol("scala/package.Seq#") -> Symbol("scala/collection/immutable/Seq#")
  )

  private val patternConstructors: Map[Symbol, (Symbol, String)] =
    fix.SemanticSupport.stdlibConstructors

  private val unsafeMethodSymbols: Set[Symbol] = Set(
    Symbol("scala/Any#asInstanceOf()."),
    Symbol("cats/effect/IO#unsafeRunSync()."),
    Symbol("cats/effect/IO#unsafeRunAsync()."),
    Symbol("cats/effect/IO#unsafeRunAsync(+1)."),
    Symbol("cats/effect/IOPlatform#unsafeRunSync()."),
    Symbol("cats/effect/IOPlatform#unsafeRunAsync()."),
    Symbol("cats/effect/IOPlatform#unsafeRunAsync(+1)."),
    Symbol("cats/effect/SyncIO#unsafeRunSync().")
  )

  def analyze(
      defn: scala.meta.Defn.Def,
      index: CatsIndex,
      widenPublic: Boolean
  )(implicit
      doc: SemanticDocument
  ): List[UsageResult] = {
    val calls = bodyCalls(defn)
    val synthetics = syntheticEvidence
    val structuralDeclines = bodyStructureDeclines(defn)

    signatureTargets(defn)
      .sortBy(target => (target.tpe.pos.start, target.constructor.value))
      .map { target =>
        kindDecline(target)
          .orElse(
            firstBodyDecline(
              defn,
              target,
              calls,
              synthetics,
              structuralDeclines,
              index
            )
          )
          .orElse(visibilityDecline(defn, widenPublic))
          .getOrElse(
            UsageResult.Abstractable(
              defn,
              target.tpe,
              target.constructor,
              target.elementType,
              requiredOps(target, calls, synthetics, index)
            )
          )
      }
  }

  def isWidenable(defn: scala.meta.Defn.Def, widenPublic: Boolean)(implicit
      doc: SemanticDocument
  ): Boolean = {
    val parents = enclosingParents(defn)
    val enclosingOwners = parents.flatMap(templateOwnerMods)
    val locallyDefined = parents.exists {
      case _: Defn.Def       => true
      case _: Term.Block     => true
      case _: Term.Function  => true
      case _: Term.Anonymous => true
      case _                 => false
    }
    val restrictedOwnerChain =
      enclosingOwners.nonEmpty && enclosingOwners.forall(isRestricted)

    widenPublic ||
    defn.mods.exists {
      case _: Mod.Private                  => true
      case Mod.Protected(Name.Anonymous()) => false
      case _: Mod.Protected                => true
      case _                               => false
    } ||
    locallyDefined ||
    restrictedOwnerChain
  }

  private def signatureTargets(defn: Defn.Def)(implicit
      doc: SemanticDocument
  ): List[Target] = {
    val parameterTypes =
      defn.paramClauseGroups
        .flatMap(_.paramClauses)
        .flatMap(_.values)
        .flatMap(param =>
          param.decltpe.map(tpe => tpe -> valueType(param.symbol))
        )
    val resultTypes = defn.decltpe.toList.map(tpe =>
      tpe -> defn.symbol.info.flatMap {
        _.signature match {
          case MethodSignature(_, _, result) => Some(result)
          case _                             => None
        }
      }
    )

    (parameterTypes ++ resultTypes)
      .flatMap { case (written, semantic) =>
        outerConcreteTargets(written, semantic)
      }
      .foldLeft(List.empty[Target]) { (targets, target) =>
        val duplicate =
          !target.constructor.isNone &&
            targets.exists(_.constructor == target.constructor)
        if (duplicate) targets else targets :+ target
      }
  }

  private def valueType(symbol: Symbol)(implicit
      doc: SemanticDocument
  ): Option[SemanticType] =
    symbol.info.flatMap {
      _.signature match {
        case ValueSignature(tpe) => Some(tpe)
        case _                   => None
      }
    }

  private def outerConcreteTargets(
      tpe: Type,
      semantic: Option[SemanticType]
  )(implicit doc: SemanticDocument): List[Target] =
    tpe match {
      case applied: Type.Apply =>
        val arguments = applied.argClause.values
        if (applied.tpe.is[Type.Lambda])
          List(
            Target(
              applied,
              Symbol.None,
              arguments.lastOption.getOrElse(applied),
              KindShape.Binary
            )
          )
        else {
          val writtenConstructor = applied.tpe.symbol
          val constructor = canonicalConstructor(
            semantic
              .flatMap(typeConstructor)
              .getOrElse(writtenConstructor)
          )
          val isConcrete =
            constructor.isGlobal &&
              writtenConstructor.isGlobal &&
              !doc
                .info(writtenConstructor)
                .exists(_.isTypeParameter)

          if (isConcrete)
            List(
              Target(
                applied,
                constructor,
                arguments.lastOption.getOrElse(applied),
                kindOf(arguments.size)
              )
            )
          else {
            val semanticArguments = semantic
              .collect { case TypeRef(_, _, values) => values }
              .getOrElse(Nil)
            arguments.zipWithIndex.flatMap { case (argument, index) =>
              outerConcreteTargets(argument, semanticArguments.lift(index))
            }
          }
        }
      case lambda: Type.Lambda =>
        List(Target(lambda, Symbol.None, lambda, KindShape.Binary))
      case name: Type.Name =>
        val writtenConstructor = name.symbol
        val constructor = canonicalConstructor(
          semantic
            .flatMap(typeConstructor)
            .getOrElse(writtenConstructor)
        )
        if (
          constructor.isGlobal &&
          writtenConstructor.isGlobal &&
          !doc.info(writtenConstructor).exists(_.isTypeParameter)
        )
          List(Target(name, constructor, name, KindShape.Star))
        else Nil
      case other =>
        other.children
          .collect { case child: Type => child }
          .flatMap(
            outerConcreteTargets(_, None)
          )
    }

  private def kindOf(arity: Int): KindShape =
    arity match {
      case 0 => KindShape.Star
      case 1 => KindShape.Unary
      case _ => KindShape.Binary
    }

  private def kindDecline(target: Target): Option[UsageResult.Declined] =
    target.kind match {
      case KindShape.Binary =>
        Some(
          UsageResult.Declined(
            target.tpe.pos,
            DeclineReason.UnsupportedKind(KindShape.Binary)
          )
        )
      case KindShape.Star | KindShape.Unary => None
    }

  private def firstBodyDecline(
      defn: Defn.Def,
      target: Target,
      calls: List[Call],
      synthetics: List[SyntheticEvidence],
      structuralDeclines: List[UsageResult.Declined],
      index: CatsIndex
  )(implicit doc: SemanticDocument): Option[UsageResult.Declined] = {
    val callDeclines = targetCalls(target, calls).flatMap { call =>
      val symbols = allCallSymbols(call, synthetics)
      symbols.find(unsafeMethodSymbols) match {
        case Some(method) =>
          Some(
            UsageResult.Declined(
              call.position,
              DeclineReason.UnsafeBody(method.displayName)
            )
          )
        case None =>
          resolveCall(symbols, index) match {
            case Left(reason) =>
              Some(UsageResult.Declined(call.position, reason))
            case Right(_) => None
          }
      }
    }
    val patternDeclines = constructorMatchDeclines(defn, target)

    (structuralDeclines ++ patternDeclines ++ callDeclines)
      .sortBy(declined =>
        (
          declined.position.start,
          declined.position.end,
          declined.reason.message
        )
      )
      .headOption
  }

  private def bodyStructureDeclines(
      defn: Defn.Def
  )(implicit doc: SemanticDocument): List[UsageResult.Declined] =
    defn.body
      .collect {
        case tree: Term.Throw if belongsTo(defn, tree) =>
          UsageResult.Declined(tree.pos, DeclineReason.UnsafeBody("throw"))
        case tree: Term.Return if belongsTo(defn, tree) =>
          UsageResult.Declined(tree.pos, DeclineReason.UnsafeBody("return"))
        case tree: Defn.Var if belongsTo(defn, tree) =>
          UsageResult.Declined(
            tree.pos,
            DeclineReason.UnsafeBody("mutable variable")
          )
        case tree @ Term.Assign(lhs, _)
            if belongsTo(defn, tree) &&
              lhs.symbol.info.exists(_.isVar) =>
          UsageResult.Declined(
            tree.pos,
            DeclineReason.UnsafeBody("assignment to mutable variable")
          )
      }
      .sortBy(declined => (declined.position.start, declined.position.end))

  private def constructorMatchDeclines(
      defn: Defn.Def,
      target: Target
  )(implicit doc: SemanticDocument): List[UsageResult.Declined] =
    defn.body
      .collect {
        case matched @ Term.Match.After_4_9_9(scrutinee: Term, casesBlock, _)
            if belongsTo(defn, matched) &&
              receiverConstructor(scrutinee).contains(target.constructor) =>
          casesBlock.cases.flatMap(
            _.pat
              .collect { case name: Term.Name =>
                patternConstructors
                  .get(name.symbol)
                  .collect {
                    case (constructor, label)
                        if canonicalConstructor(constructor) ==
                          target.constructor =>
                      UsageResult.Declined(
                        name.pos,
                        DeclineReason.ConcreteConstructorMatch(label)
                      )
                  }
              }
              .flatten
          )
      }
      .flatten
      .sortBy(declined => (declined.position.start, declined.reason.message))

  private def requiredOps(
      target: Target,
      calls: List[Call],
      synthetics: List[SyntheticEvidence],
      index: CatsIndex
  )(implicit doc: SemanticDocument): List[RequiredOp] =
    targetCalls(target, calls)
      .flatMap { call =>
        resolveCall(allCallSymbols(call, synthetics), index).toOption.map {
          resolved =>
            RequiredOp(resolved.owner, call.position, resolved.kind)
        }
      }
      .distinctBy(op => (op.method, op.position.start, op.position.end))
      .sortBy(op => (op.position.start, op.method.value))

  private def targetCalls(target: Target, calls: List[Call])(implicit
      doc: SemanticDocument
  ): List[Call] =
    calls.filter(call =>
      receiverConstructor(call.receiver).contains(target.constructor)
    )

  private def resolveCall(
      symbols: List[Symbol],
      index: CatsIndex
  ): Either[DeclineReason, Resolved] = {
    val firstHit = symbols.iterator
      .map(symbol => lookupSymbol(symbol, index))
      .find(_ != NotFound)

    firstHit match {
      case Some(Rejected(reason)) => Left(reason)
      case Some(Found(resolved))  => chooseCapability(resolved, index)
      case Some(NotFound) | None =>
        Left(
          DeclineReason.NoCapability(
            symbols.headOption.getOrElse(Symbol.None)
          )
        )
    }
  }

  private def lookupSymbol(symbol: Symbol, index: CatsIndex): Lookup =
    index.resolveSyntax(symbol) match {
      case Some(capability) =>
        Found(List(fromCapability(capability)))
      case None =>
        val providers = index.providersOf(symbol)
        if (providers.nonEmpty)
          Found(
            providers
              .groupBy(_.owner)
              .toList
              .sortBy(_._1.value)
              .map { case (_, capabilities) =>
                fromCapabilities(capabilities)
              }
          )
        else lookupStdlib(symbol, index)
    }

  private def lookupStdlib(symbol: Symbol, index: CatsIndex): Lookup = {
    val entries = index.resolveStdlib(symbol)
    entries
      .collectFirst { case StdlibEntry(_, StdlibMapping.ToDecline(reason)) =>
        Rejected(stdlibDecline(reason, symbol))
      }
      .getOrElse {
        val resolved = entries.collect {
          case StdlibEntry(
                _,
                StdlibMapping.ToCapability(owner, method)
              ) =>
            fromStdlib(owner, method, index)
        }
        if (resolved.nonEmpty) Found(resolved) else NotFound
      }
  }

  private def stdlibDecline(reason: String, method: Symbol): DeclineReason =
    reason match {
      case "ConcreteConstructorMatch" =>
        DeclineReason.ConcreteConstructorMatch(method.displayName)
      case "OrderOrIndexSpecific" =>
        DeclineReason.OrderOrIndexSpecific(method.displayName)
      case "UnsafeBody" =>
        DeclineReason.UnsafeBody(method.displayName)
      case _ => DeclineReason.NoCapability(method)
    }

  private def fromCapability(capability: Capability): Resolved =
    Resolved(
      capability.owner,
      List(capability.typeclass),
      capability.kind
    )

  private def fromCapabilities(capabilities: List[Capability]): Resolved = {
    val stable = capabilities.sortBy(capability =>
      (
        KindShape.arity(capability.kind),
        capability.typeclass.value,
        capability.method.value
      )
    )
    Resolved(
      stable.head.owner,
      stable.map(_.typeclass).distinct.sortBy(_.value),
      stable.head.kind
    )
  }

  private def fromStdlib(
      owner: Symbol,
      method: Symbol,
      index: CatsIndex
  ): Resolved = {
    val capabilities = index
      .providersOf(method)
      .filter(_.owner == owner)
    if (capabilities.nonEmpty) fromCapabilities(capabilities)
    else Resolved(owner, Nil, KindShape.Unary)
  }

  private def chooseCapability(
      resolved: List[Resolved],
      index: CatsIndex
  ): Either[DeclineReason, Resolved] = {
    val byOwner = resolved
      .groupBy(_.owner)
      .toList
      .sortBy(_._1.value)
      .map { case (owner, values) =>
        owner -> Resolved(
          owner,
          values.flatMap(_.providers).distinct.sortBy(_.value),
          values.map(_.kind).sortBy(KindShape.arity).head
        )
      }
    val unrelated = byOwner.combinations(2).exists {
      case List((_, left), (_, right)) =>
        !capabilitiesRelated(left.providers, right.providers, index)
      case _ => false
    }

    if (unrelated)
      Left(DeclineReason.AmbiguousCapability(byOwner.map(_._1)))
    else
      Right(
        byOwner
          .map(_._2)
          .sortBy(value =>
            (minimumDepth(value.providers, index), value.owner.value)
          )
          .head
      )
  }

  private def capabilitiesRelated(
      left: List[Symbol],
      right: List[Symbol],
      index: CatsIndex
  ): Boolean =
    left.exists { leftTypeclass =>
      right.exists { rightTypeclass =>
        leftTypeclass == rightTypeclass ||
        index.isAncestor(leftTypeclass, rightTypeclass) ||
        index.isAncestor(rightTypeclass, leftTypeclass)
      }
    }

  private def minimumDepth(providers: List[Symbol], index: CatsIndex): Int =
    providers.map(index.depth).minOption.getOrElse(Int.MaxValue)

  private def bodyCalls(defn: Defn.Def)(implicit
      doc: SemanticDocument
  ): List[Call] =
    defn.body
      .collect {
        case select @ Term.Select(receiver: Term, method: Term.Name)
            if belongsTo(defn, select) =>
          Call(receiver, method.symbol, method.pos, select.pos)
        case apply @ Term.Apply.After_4_6_0(receiver: Term.Name, _)
            if belongsTo(defn, apply) =>
          Call(receiver, Symbol.None, apply.pos, apply.pos)
      }
      .sortBy(call =>
        (call.position.start, call.position.end, call.method.value)
      )

  private def receiverConstructor(term: Term)(implicit
      doc: SemanticDocument
  ): Option[Symbol] =
    term.symbol.info.flatMap { info =>
      info.signature match {
        case ValueSignature(tpe) =>
          typeConstructor(tpe).map(canonicalConstructor)
        case MethodSignature(_, _, tpe) =>
          typeConstructor(tpe).map(canonicalConstructor)
        case _ => None
      }
    }

  private def typeConstructor(tpe: SemanticType): Option[Symbol] =
    tpe match {
      case TypeRef(_, symbol, _) => Some(symbol)
      case _                     => None
    }

  private def canonicalConstructor(symbol: Symbol): Symbol =
    constructorAliases.getOrElse(symbol, symbol)

  private def syntheticEvidence(implicit
      doc: SemanticDocument
  ): List[SyntheticEvidence] =
    fix.SemanticSupport.syntheticEvidence

  private def allCallSymbols(
      call: Call,
      synthetics: List[SyntheticEvidence]
  )(implicit doc: SemanticDocument): List[Symbol] =
    fix.SemanticSupport
      .symbolsAt(call.span, call.method, synthetics)
      .flatMap(symbol => symbol :: overriddenBy(symbol))
      .distinct

  /** A symbol and the declarations it overrides, nearest first.
    *
    * The compiler resolves `xs.filter` on a `List` to
    * `scala/collection/immutable/List#filter().`, not to the `IterableOps`
    * declaration it inherits. Without the override chain the capability table
    * has to name every concrete collection separately -- one row for `List`,
    * one for `Vector`, one for `Seq` -- which is a row per class per method and
    * silently misses whichever pair nobody thought of. With it, one row on the
    * trait answers for every collection that inherits it.
    */
  private def overriddenBy(
      symbol: Symbol
  )(implicit doc: SemanticDocument): List[Symbol] =
    symbol.info.toList.flatMap(_.overriddenSymbols)

  private def positionsOverlap(left: Position, right: Position): Boolean =
    left != Position.None &&
      right != Position.None &&
      left.start <= right.end &&
      right.start <= left.end

  private def visibilityDecline(
      defn: Defn.Def,
      widenPublic: Boolean
  )(implicit doc: SemanticDocument): Option[UsageResult.Declined] =
    if (isWidenable(defn, widenPublic)) None
    else
      Some(
        UsageResult.Declined(
          defn.name.pos,
          DeclineReason.PublicBoundary(defn.name.value)
        )
      )

  private def belongsTo(defn: Defn.Def, tree: Tree): Boolean =
    nearestEnclosingDef(tree).contains(defn)

  private def nearestEnclosingDef(tree: Tree): Option[Defn.Def] =
    tree.parent match {
      case Some(parent: Defn.Def) => Some(parent)
      case Some(parent)           => nearestEnclosingDef(parent)
      case None                   => None
    }

  private def enclosingParents(tree: Tree): List[Tree] =
    tree.parent match {
      case Some(parent) => parent :: enclosingParents(parent)
      case None         => Nil
    }

  private def templateOwnerMods(tree: Tree): Option[List[Mod]] =
    tree match {
      case owner: Defn.Class  => Some(owner.mods)
      case owner: Defn.Trait  => Some(owner.mods)
      case owner: Defn.Object => Some(owner.mods)
      case _                  => None
    }

  private def isRestricted(mods: List[Mod]): Boolean =
    mods.exists {
      case _: Mod.Private   => true
      case _: Mod.Protected => true
      case _                => false
    }
}
