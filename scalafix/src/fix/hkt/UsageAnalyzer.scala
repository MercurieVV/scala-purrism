package fix.hkt

import scala.meta._
import scala.meta.inputs.Position

import scalafix.v1.ApplyTree
import scalafix.v1.FunctionTree
import scalafix.v1.IdTree
import scalafix.v1.MacroExpansionTree
import scalafix.v1.MethodSignature
import scalafix.v1.OriginalSubTree
import scalafix.v1.OriginalTree
import scalafix.v1.SelectTree
import scalafix.v1.SemanticDocument
import scalafix.v1.SemanticTree
import scalafix.v1.SemanticType
import scalafix.v1.Symbol
import scalafix.v1.TypeApplyTree
import scalafix.v1.TypeRef
import scalafix.v1.ValueSignature
import scalafix.v1.XtensionTreeScalafix

final case class RequiredOp(method: Symbol, position: Position, kind: KindShape)

sealed trait DeclineReason { def message: String }

object DeclineReason {
  final case class ConcreteConstructorMatch(what: String) extends DeclineReason {
    def message: String = s"concrete constructor pattern cannot be abstracted: $what"
  }
  final case class OrderOrIndexSpecific(what: String) extends DeclineReason {
    def message: String = s"order- or index-specific operation cannot be abstracted: $what"
  }
  final case class UnsupportedKind(shape: KindShape) extends DeclineReason {
    def message: String = s"unsupported type-constructor kind: ${KindShape.render(shape)}"
  }
  final case class PublicBoundary(defName: String) extends DeclineReason {
    def message: String = s"public API boundary cannot be widened: $defName"
  }
  final case class AmbiguousCapability(candidates: List[Symbol]) extends DeclineReason {
    def message: String =
      s"operation has unrelated capability roots: ${candidates.map(_.value).mkString(", ")}"
  }
  final case class NoCapability(method: Symbol) extends DeclineReason {
    def message: String = s"no indexed capability for operation: ${method.value}"
  }
  final case class UnsafeBody(what: String) extends DeclineReason {
    def message: String = s"unsafe body cannot be abstracted: $what"
  }
  final case class NameConflict(tried: List[String]) extends DeclineReason {
    def message: String = s"no conflict-free type parameter name among: ${tried.mkString(", ")}"
  }
  final case class TooManyConstraints(candidate: List[Symbol], max: Int)
      extends DeclineReason {
    def message: String =
      s"candidate requires ${candidate.size} constraints, exceeding maximum $max: " +
        candidate.map(_.value).mkString(", ")
  }
  case object MissingEvidence extends DeclineReason {
    def message: String = "semantic evidence required for usage analysis is missing"
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
  final case class Declined(position: Position, reason: DeclineReason) extends UsageResult
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
      position: Position
  )

  private final case class Resolved(
      owner: Symbol,
      providers: List[Symbol],
      kind: KindShape
  )

  private final case class SyntheticEvidence(
      position: Position,
      symbols: List[Symbol]
  )

  private val concretePatternSymbols: Set[Symbol] = Set(
    Symbol("scala/None."),
    Symbol("scala/Some."),
    Symbol("scala/package.Nil."),
    Symbol("scala/package.None."),
    Symbol("scala/package.Some."),
    Symbol("scala/collection/immutable/Nil."),
    Symbol("scala/util/Left."),
    Symbol("scala/util/Right.")
  )

  private val constructorAliases: Map[Symbol, Symbol] = Map(
    Symbol("scala/package.List#") -> Symbol("scala/collection/immutable/List#"),
    Symbol("scala/package.Seq#") -> Symbol("scala/collection/immutable/Seq#")
  )

  private val orderOrIndexSymbols: Set[Symbol] = Set(
    Symbol("scala/Array#apply()."),
    Symbol("scala/collection/IterableOps#head()."),
    Symbol("scala/collection/IterableOps#tail()."),
    Symbol("scala/collection/LinearSeqOps#apply()."),
    Symbol("scala/collection/LinearSeqOps#length()."),
    Symbol("scala/collection/SeqOps#apply()."),
    Symbol("scala/collection/SeqOps#length()."),
    Symbol("scala/collection/SeqOps#sorted()."),
    Symbol("scala/collection/immutable/List#apply()."),
    Symbol("scala/collection/immutable/List#head()."),
    Symbol("scala/collection/immutable/List#length()."),
    Symbol("scala/collection/immutable/List#tail().")
  )

  private val unsafeMethodSymbols: Set[Symbol] = Set(
    Symbol("cats/effect/IO#unsafeRunAndForget()."),
    Symbol("cats/effect/IO#unsafeRunCancelable()."),
    Symbol("cats/effect/IO#unsafeRunSync()."),
    Symbol("cats/effect/IO#unsafeRunTimed()."),
    Symbol("cats/effect/IO#unsafeToFuture()."),
    Symbol("cats/effect/SyncIO#unsafeRunSync()."),
    Symbol("scala/Array#update()."),
    Symbol("scala/Any#asInstanceOf().")
  )

  def analyze(defn: scala.meta.Defn.Def, index: CatsIndex, widenPublic: Boolean)(implicit
      doc: SemanticDocument
  ): List[UsageResult] = {
    val targets = signatureTargets(defn)
    val calls = bodyCalls(defn)
    val synthetics = syntheticEvidence
    val globalDecline = firstGlobalDecline(defn, calls, synthetics)

    targets
      .sortBy(_.tpe.pos.start)
      .map { target =>
        globalDecline
          .orElse(kindDecline(target))
          .orElse(analyzeCalls(target, calls, synthetics, index))
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
      case _: Mod.Private => true
      case Mod.Protected(within) =>
        within.syntax.nonEmpty
      case _ => false
    } ||
    locallyDefined ||
    restrictedOwnerChain
  }

  private def signatureTargets(defn: Defn.Def)(implicit
      doc: SemanticDocument
  ): List[Target] = {
    val writtenTypes =
      defn.paramClauseGroups
        .flatMap(_.paramClauses)
        .flatMap(_.values)
        .flatMap(_.decltpe) ++ defn.decltpe.toList

    writtenTypes
      .flatMap(outerConcreteTargets)
      .foldLeft(List.empty[Target]) { (acc, target) =>
        if (
          (target.constructor.isNone && target.kind != KindShape.Binary) ||
          acc.exists(existing =>
            !target.constructor.isNone &&
              existing.constructor == target.constructor
          )
        ) acc
        else acc :+ target
      }
  }

  private def outerConcreteTargets(tpe: Type)(implicit
      doc: SemanticDocument
  ): List[Target] =
    tpe match {
      case applied: Type.Apply =>
        val args = applied.argClause.values
        val writtenConstructor = applied.tpe.symbol
        val constructor = canonicalConstructor(writtenConstructor)
        if (applied.tpe.is[Type.Lambda])
          List(
            Target(
              applied,
              Symbol.None,
              args.lastOption.getOrElse(applied),
              KindShape.Binary
            )
          )
        else if (
          writtenConstructor.isGlobal &&
          !doc.info(writtenConstructor).exists(_.isTypeParameter) &&
          !isStructuralWrapper(constructor)
        )
          List(
            Target(
              applied,
              constructor,
              args.lastOption.getOrElse(applied),
              kindOf(args.size)
            )
          )
        else args.flatMap(outerConcreteTargets)
      case lambda: Type.Lambda =>
        List(Target(lambda, lambda.symbol, lambda, KindShape.Binary))
      case _ => Nil
    }

  private def isStructuralWrapper(symbol: Symbol): Boolean = {
    val value = symbol.value
    value.startsWith("scala/Function") ||
    value.startsWith("scala/Tuple") ||
    value.startsWith("scala/runtime/Tuple")
  }

  private def kindOf(arity: Int): KindShape =
    arity match {
      case 0 => KindShape.Star
      case 1 => KindShape.Unary
      case _ => KindShape.Binary
    }

  private def firstGlobalDecline(
      defn: Defn.Def,
      calls: List[Call],
      synthetics: List[SyntheticEvidence]
  )(implicit doc: SemanticDocument): Option[UsageResult.Declined] = {
    val structural = defn.body.collect {
      case tree: Term.Throw if belongsTo(defn, tree) =>
        UsageResult.Declined(tree.pos, DeclineReason.UnsafeBody("throw"))
      case tree: Term.Return if belongsTo(defn, tree) =>
        UsageResult.Declined(tree.pos, DeclineReason.UnsafeBody("return"))
      case tree: Defn.Var if belongsTo(defn, tree) =>
        UsageResult.Declined(tree.pos, DeclineReason.UnsafeBody("mutable variable"))
      case tree: Term.Assign if belongsTo(defn, tree) =>
        UsageResult.Declined(tree.pos, DeclineReason.UnsafeBody("assignment"))
    }

    val constructorMatches = defn.body.collect {
      case branch: Case if belongsTo(defn, branch) =>
        branch.pat.collect {
          case name: Term.Name if concretePatternSymbols(name.symbol) =>
            UsageResult.Declined(
              name.pos,
              DeclineReason.ConcreteConstructorMatch(name.symbol.value)
            )
        }
    }.flatten

    val unsafeCalls = calls.flatMap { call =>
      allCallSymbols(call, synthetics).collectFirst {
        case symbol if isUnsafeMethod(symbol) =>
          UsageResult.Declined(
            call.position,
            DeclineReason.UnsafeBody(symbol.value)
          )
      }
    }

    (structural ++ constructorMatches ++ unsafeCalls)
      .sortBy(_.position.start)
      .headOption
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

  private def analyzeCalls(
      target: Target,
      calls: List[Call],
      synthetics: List[SyntheticEvidence],
      index: CatsIndex
  )(implicit doc: SemanticDocument): Option[UsageResult.Declined] =
    calls
      .filter(call => receiverConstructor(call.receiver).contains(target.constructor))
      .sortBy(_.position.start)
      .flatMap { call =>
        val symbols = allCallSymbols(call, synthetics)
        symbols
          .find(orderOrIndexSymbols)
          .map(symbol =>
            UsageResult.Declined(
              call.position,
              DeclineReason.OrderOrIndexSpecific(symbol.value)
            )
          )
          .orElse {
            resolveCall(symbols, index) match {
              case Left(reason) =>
                Some(UsageResult.Declined(call.position, reason))
              case Right(_) => None
            }
          }
      }
      .headOption

  private def requiredOps(
      target: Target,
      calls: List[Call],
      synthetics: List[SyntheticEvidence],
      index: CatsIndex
  )(implicit doc: SemanticDocument): List[RequiredOp] =
    calls
      .filter(call => receiverConstructor(call.receiver).contains(target.constructor))
      .sortBy(_.position.start)
      .flatMap { call =>
        resolveCall(allCallSymbols(call, synthetics), index).toOption.map { resolved =>
          RequiredOp(resolved.owner, call.position, resolved.kind)
        }
      }
      .distinctBy(op => (op.method, op.position.start, op.position.end))

  private def resolveCall(
      symbols: List[Symbol],
      index: CatsIndex
  ): Either[DeclineReason, Resolved] = {
    val resolved = symbols.flatMap(resolveSymbol(_, index))
    val byOwner = resolved.groupBy(_.owner).toList.sortBy(_._1.value)

    if (byOwner.isEmpty)
      symbols.find(!_.isNone) match {
        case Some(method) => Left(DeclineReason.NoCapability(method))
        case None         => Left(DeclineReason.MissingEvidence)
      }
    else {
      val roots = byOwner.map(_._1)
      val unrelated = roots.combinations(2).exists {
        case List(left, right) =>
          val leftProviders = byOwner.find(_._1 == left).toList.flatMap(_._2).flatMap(_.providers)
          val rightProviders =
            byOwner.find(_._1 == right).toList.flatMap(_._2).flatMap(_.providers)
          !capabilitiesRelated(leftProviders, rightProviders, index)
        case _ => false
      }

      if (unrelated) Left(DeclineReason.AmbiguousCapability(roots))
      else
        Right(
          byOwner
            .flatMap(_._2)
            .sortBy(resolved => (minimumDepth(resolved.providers, index), resolved.owner.value))
            .head
        )
    }
  }

  private def resolveSymbol(symbol: Symbol, index: CatsIndex): List[Resolved] =
    index.resolveSyntax(symbol) match {
      case Some(capability) =>
        List(
          Resolved(
            capability.owner,
            List(capability.typeclass),
            capability.kind
          )
        )
      case None =>
        val providers = index.providersOf(symbol)
        if (providers.nonEmpty)
          providers
            .groupBy(_.owner)
            .toList
            .sortBy(_._1.value)
            .map { case (owner, capabilities) =>
              Resolved(
                owner,
                capabilities.map(_.typeclass).distinct.sortBy(_.value),
                capabilities.map(_.kind).sortBy(KindShape.arity).head
              )
            }
        else
          index.primitiveOwner(symbol).toList.map { owner =>
            val ownerProviders = index.providersOf(owner)
            Resolved(
              owner,
              ownerProviders.map(_.typeclass).distinct.sortBy(_.value),
              ownerProviders.headOption.map(_.kind).getOrElse(KindShape.Unary)
            )
          }
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
    providers.map(index.depth).minOption.getOrElse(0)

  private def bodyCalls(defn: Defn.Def)(implicit
      doc: SemanticDocument
  ): List[Call] =
    defn.body.collect {
      case select @ Term.Select(receiver: Term, method: Term.Name)
          if belongsTo(defn, select) =>
        Call(receiver, method.symbol, method.pos)
      case apply @ Term.Apply.After_4_6_0(receiver: Term.Name, _)
          if belongsTo(defn, apply) =>
        Call(receiver, Symbol.None, apply.pos)
    }.sortBy(_.position.start)

  private def receiverConstructor(term: Term)(implicit
      doc: SemanticDocument
  ): Option[Symbol] =
    term.symbol.info.flatMap { info =>
      info.signature match {
        case ValueSignature(tpe)        => typeConstructor(tpe).map(canonicalConstructor)
        case MethodSignature(_, _, tpe) => typeConstructor(tpe).map(canonicalConstructor)
        case _                          => None
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
    doc.synthetics
      .flatMap { synthetic =>
        val (positions, symbols) = flattenSynthetic(synthetic)
        positions.map(position =>
          SyntheticEvidence(position, symbols.distinct.sortBy(_.value))
        )
      }
      .toList
      .sortBy(evidence =>
        (
          evidence.position.start,
          evidence.position.end,
          evidence.symbols.map(_.value).mkString("\u0000")
        )
      )

  private def flattenSynthetic(
      tree: SemanticTree
  ): (List[Position], List[Symbol]) =
    tree match {
      case IdTree(info) => (Nil, List(info.symbol))
      case ApplyTree(function, arguments) =>
        combine(function :: arguments)
      case SelectTree(qualifier, id) =>
        combine(List(qualifier, id))
      case TypeApplyTree(function, _) =>
        flattenSynthetic(function)
      case FunctionTree(parameters, body) =>
        combine(parameters :+ body)
      case MacroExpansionTree(beforeExpansion, _) =>
        flattenSynthetic(beforeExpansion)
      case OriginalTree(tree) =>
        (List(tree.pos), Nil)
      case OriginalSubTree(tree) =>
        (List(tree.pos), Nil)
      case _ => (Nil, Nil)
    }

  private def combine(
      trees: List[SemanticTree]
  ): (List[Position], List[Symbol]) =
    trees.foldLeft((List.empty[Position], List.empty[Symbol])) {
      case ((positions, symbols), tree) =>
        val (treePositions, treeSymbols) = flattenSynthetic(tree)
        (positions ++ treePositions, symbols ++ treeSymbols)
    }

  private def allCallSymbols(
      call: Call,
      synthetics: List[SyntheticEvidence]
  ): List[Symbol] =
    (call.method :: synthetics
      .filter(evidence => positionsOverlap(call.position, evidence.position))
      .flatMap(_.symbols))
      .filter(!_.isNone)
      .distinct

  private def positionsOverlap(left: Position, right: Position): Boolean =
    left != Position.None &&
      right != Position.None &&
      left.start <= right.end &&
      right.start <= left.end

  private def isUnsafeMethod(symbol: Symbol): Boolean =
    unsafeMethodSymbols(symbol) ||
      symbol.value.startsWith("scala/collection/mutable/")

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
