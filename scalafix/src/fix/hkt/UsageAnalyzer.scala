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

  def analyze(
      defn: scala.meta.Defn.Def,
      index: CatsIndex,
      widenPublic: Boolean
  )(implicit
      doc: SemanticDocument
  ): List[UsageResult] = {
    val targets = signatureTargets(defn)
    val calls = bodyCalls(defn)
    val synthetics = syntheticEvidence
    val structuralDecline = firstStructuralDecline(defn)

    targets
      .sortBy(_.tpe.pos.start)
      .map { target =>
        kindDecline(target)
          .orElse(structuralDecline)
          .orElse(constructorMatchDecline(defn, target))
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
          param.decltpe.map(tpe => (tpe, valueType(param.symbol)))
        )
    val resultType = defn.decltpe.toList.map(tpe =>
      (
        tpe,
        defn.symbol.info.flatMap {
          _.signature match {
            case MethodSignature(_, _, result) => Some(result)
            case _                             => None
          }
        }
      )
    )

    (parameterTypes ++ resultType)
      .flatMap { case (written, semantic) =>
        outerConcreteTargets(written, semantic)
      }
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
  )(implicit
      doc: SemanticDocument
  ): List[Target] =
    tpe match {
      case applied: Type.Apply =>
        val args = applied.argClause.values
        val semanticArguments = semantic
          .collect { case TypeRef(_, _, arguments) =>
            arguments
          }
          .getOrElse(Nil)
        val writtenConstructor = applied.tpe.symbol
        val constructor =
          semantic.flatMap(typeConstructor).getOrElse(writtenConstructor)
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
          !constructor.isNone
        )
          List(
            Target(
              applied,
              constructor,
              args.lastOption.getOrElse(applied),
              kindOf(args.size)
            )
          )
        else
          args.zipWithIndex.flatMap { case (argument, index) =>
            outerConcreteTargets(argument, semanticArguments.lift(index))
          }
      case lambda: Type.Lambda =>
        List(Target(lambda, lambda.symbol, lambda, KindShape.Binary))
      case _ => Nil
    }

  private def kindOf(arity: Int): KindShape =
    arity match {
      case 0 => KindShape.Star
      case 1 => KindShape.Unary
      case _ => KindShape.Binary
    }

  private def firstStructuralDecline(
      defn: Defn.Def
  ): Option[UsageResult.Declined] = {
    val structural = defn.body.collect {
      case tree: Term.Throw if belongsTo(defn, tree) =>
        UsageResult.Declined(tree.pos, DeclineReason.UnsafeBody("throw"))
      case tree: Term.Return if belongsTo(defn, tree) =>
        UsageResult.Declined(tree.pos, DeclineReason.UnsafeBody("return"))
      case tree: Defn.Var if belongsTo(defn, tree) =>
        UsageResult.Declined(
          tree.pos,
          DeclineReason.UnsafeBody("mutable variable")
        )
      case tree: Term.Assign if belongsTo(defn, tree) =>
        UsageResult.Declined(tree.pos, DeclineReason.UnsafeBody("assignment"))
    }

    structural
      .sortBy(_.position.start)
      .headOption
  }

  private def constructorMatchDecline(
      defn: Defn.Def,
      target: Target
  )(implicit doc: SemanticDocument): Option[UsageResult.Declined] =
    defn.body
      .collect {
        case matched @ Term.Match.After_4_9_9(scrutinee: Term, casesBlock, _)
            if belongsTo(defn, matched) &&
              receiverConstructor(scrutinee).contains(target.constructor) =>
          casesBlock.cases.flatMap(_.pat.collect {
            case name: Term.Name if name.symbol.isGlobal =>
              UsageResult.Declined(
                name.pos,
                DeclineReason.ConcreteConstructorMatch(name.symbol.value)
              )
          })
      }
      .flatten
      .sortBy(_.position.start)
      .headOption

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
      .filter(call =>
        receiverConstructor(call.receiver).contains(target.constructor)
      )
      .sortBy(_.position.start)
      .flatMap { call =>
        val symbols = allCallSymbols(call, synthetics)
        resolveCall(symbols, index) match {
          case Left(reason: DeclineReason.NoCapability) =>
            unsafeCall(symbols, target)
              .map(symbol =>
                UsageResult.Declined(
                  call.position,
                  DeclineReason.UnsafeBody(symbol.value)
                )
              )
              .orElse(
                orderOrIndexCall(symbols, target).map(symbol =>
                  UsageResult.Declined(
                    call.position,
                    DeclineReason.OrderOrIndexSpecific(symbol.value)
                  )
                )
              )
              .orElse(Some(UsageResult.Declined(call.position, reason)))
          case Left(reason) =>
            Some(UsageResult.Declined(call.position, reason))
          case Right(_) =>
            None
        }
      }
      .headOption

  private def unsafeCall(
      symbols: List[Symbol],
      target: Target
  )(implicit doc: SemanticDocument): Option[Symbol] =
    symbols.find(isUnsafeCast).orElse {
      symbols.find { symbol =>
        symbol != Symbol.None &&
        doc.info(symbol).exists { info =>
          (info.isImplicit || info.isGiven) &&
          !signatureMentions(info.signature, target.elementType.symbol)
        }
      }
    }

  private def isUnsafeCast(symbol: Symbol)(implicit
      doc: SemanticDocument
  ): Boolean =
    doc.info(symbol).exists { info =>
      info.signature match {
        case MethodSignature(typeParameters, _, TypeRef(_, result, _)) =>
          typeParameters.exists(_.symbol == result)
        case _ => false
      }
    }

  private def signatureMentions(
      signature: scalafix.v1.Signature,
      target: Symbol
  ): Boolean =
    signature match {
      case ValueSignature(tpe)        => semanticTypeMentions(tpe, target)
      case MethodSignature(_, _, tpe) => semanticTypeMentions(tpe, target)
      case _                          => false
    }

  private def semanticTypeMentions(tpe: SemanticType, target: Symbol): Boolean =
    tpe match {
      case TypeRef(prefix, symbol, arguments) =>
        symbol == target ||
        semanticTypeMentions(prefix, target) ||
        arguments.exists(semanticTypeMentions(_, target))
      case _ => false
    }

  private def orderOrIndexCall(
      symbols: List[Symbol],
      target: Target
  ): Option[Symbol] =
    symbols.find { symbol =>
      symbol.isGlobal &&
      symbol.owner != target.constructor
    }

  private def requiredOps(
      target: Target,
      calls: List[Call],
      synthetics: List[SyntheticEvidence],
      index: CatsIndex
  )(implicit doc: SemanticDocument): List[RequiredOp] =
    calls
      .filter(call =>
        receiverConstructor(call.receiver).contains(target.constructor)
      )
      .sortBy(_.position.start)
      .flatMap { call =>
        resolveCall(allCallSymbols(call, synthetics), index).toOption.map {
          resolved =>
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
          val leftProviders =
            byOwner.find(_._1 == left).toList.flatMap(_._2).flatMap(_.providers)
          val rightProviders =
            byOwner
              .find(_._1 == right)
              .toList
              .flatMap(_._2)
              .flatMap(_.providers)
          !capabilitiesRelated(leftProviders, rightProviders, index)
        case _ => false
      }

      if (unrelated) Left(DeclineReason.AmbiguousCapability(roots))
      else
        Right(
          byOwner
            .flatMap(_._2)
            .sortBy(resolved =>
              (minimumDepth(resolved.providers, index), resolved.owner.value)
            )
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
    defn.body
      .collect {
        case select @ Term.Select(receiver: Term, method: Term.Name)
            if belongsTo(defn, select) =>
          Call(receiver, method.symbol, method.pos)
        case apply @ Term.Apply.After_4_6_0(receiver: Term.Name, _)
            if belongsTo(defn, apply) =>
          Call(receiver, Symbol.None, apply.pos)
      }
      .sortBy(_.position.start)

  private def receiverConstructor(term: Term)(implicit
      doc: SemanticDocument
  ): Option[Symbol] =
    term.symbol.info.flatMap { info =>
        info.signature match {
          case ValueSignature(tpe) =>
            typeConstructor(tpe)
          case MethodSignature(_, _, tpe) =>
            typeConstructor(tpe)
          case _ => None
        }
      }

  private def typeConstructor(tpe: SemanticType): Option[Symbol] =
    tpe match {
      case TypeRef(_, symbol, _) => Some(symbol)
      case _                     => None
    }

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
      .sortWith { (left, right) =>
        if (left.position.start != right.position.start)
          left.position.start < right.position.start
        else if (left.position.end != right.position.end)
          left.position.end < right.position.end
        else compareSymbols(left.symbols, right.symbols) < 0
      }

  private def compareSymbols(left: List[Symbol], right: List[Symbol]): Int =
    (left, right) match {
      case (Nil, Nil) => 0
      case (Nil, _)   => -1
      case (_, Nil)   => 1
      case (leftHead :: leftTail, rightHead :: rightTail) =>
        val compared = leftHead.value.compareTo(rightHead.value)
        if (compared == 0) compareSymbols(leftTail, rightTail)
        else compared
    }

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
