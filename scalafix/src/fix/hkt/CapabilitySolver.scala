package fix.hkt

import scalafix.v1.Symbol

object CapabilitySolver {
  final case class Solution(
      constraints: List[Symbol],
      extraTypeParams: List[String],
      strengthSum: Int
  )

  def solve(ops: List[RequiredOp], index: CatsIndex, maxConstraints: Int)
      : Either[DeclineReason, Solution] = {
    val requiredOwners = ops.foldLeft[Either[DeclineReason, List[Symbol]]](Right(Nil)) {
      case (Right(owners), op) =>
        index.primitiveOwner(op.method) match {
          case Some(owner) if owners.contains(owner) => Right(owners)
          case Some(owner)                           => Right(owners :+ owner)
          case None                                  => Left(DeclineReason.NoCapability(op.method))
        }
      case (left, _) => left
    }

    requiredOwners.flatMap { _ =>
      ops
        .find(_.kind != KindShape.Unary)
        .filter(_ => ops.forall(_.kind != KindShape.Unary)) match {
        case Some(op) => Left(DeclineReason.UnsupportedKind(op.kind))
        case None =>
          val allCandidates = candidates(ops, index)
          val singles = allCandidates.filter(_.size == 1)
          val multi = allCandidates.find(_.size != 1).getOrElse(Nil)
          if (singles.isEmpty && multi.size > maxConstraints)
            Left(DeclineReason.TooManyConstraints(multi, maxConstraints))
          else
            rank(allCandidates, index).headOption match {
              case Some(constraints) =>
                Right(
                  Solution(
                    constraints,
                    extraTypeParams(constraints, index),
                    strength(constraints, index)
                  )
                )
              case None => Left(DeclineReason.TooManyConstraints(multi, maxConstraints))
            }
      }
    }
  }

  def candidates(ops: List[RequiredOp], index: CatsIndex): List[List[Symbol]] = {
    val owners = ops.flatMap(op => index.primitiveOwner(op.method)).distinct
    val required = owners.flatMap(ownerTypeclass(_, index)).distinct
    val singles = index.typeclasses.valuesIterator
      .filter(_.kind == KindShape.Unary)
      .filter { typeclass =>
        val provided = index.capabilities
          .getOrElse(typeclass.symbol, Nil)
          .map(_.owner)
          .toSet
        owners.forall(provided)
      }
      .map(typeclass => List(typeclass.symbol))
      .toList

    val antichain = required.filterNot { candidate =>
      required.exists(other => index.isAncestor(candidate, other))
    }

    (singles :+ antichain)
      .map(normalize)
      .distinct
  }

  def rank(candidates: List[List[Symbol]], index: CatsIndex): List[List[Symbol]] = {
    val normalized = candidates.map(normalize)
    val sorted = normalized.sortWith { (left, right) =>
      compareKeys(left, right, index) < 0
    }
    sorted.sliding(2).foreach {
      case List(left, right) =>
        require(
          compareKeys(left, right, index) != 0,
          s"candidate ranking tie: ${left.map(_.value)} and ${right.map(_.value)}"
        )
      case _ => ()
    }
    sorted
  }

  def supports(typeclass: Symbol, index: CatsIndex): Boolean = {
    val ops = index.capabilities
      .getOrElse(typeclass, Nil)
      .filter(capability => ownerTypeclass(capability.owner, index).contains(typeclass))
      .map(capability => RequiredOp(capability.method, scala.meta.inputs.Position.None, capability.kind))
    solve(ops, index, Int.MaxValue).isRight
  }

  private def ownerTypeclass(owner: Symbol, index: CatsIndex): Option[Symbol] = {
    val value = owner.value
    val separator = value.indexOf('#')
    if (separator < 0) None
    else {
      val typeclass = Symbol(value.substring(0, separator + 1))
      index.typeclasses.get(typeclass).map(_.symbol)
    }
  }

  private def normalize(constraints: List[Symbol]): List[Symbol] =
    constraints.distinct.sortWith((left, right) => left.value.compareTo(right.value) < 0)

  private def strength(constraints: List[Symbol], index: CatsIndex): Int =
    constraints.map(index.depth).sum

  private def compareKeys(left: List[Symbol], right: List[Symbol], index: CatsIndex): Int = {
    val count = Integer.compare(left.size, right.size)
    if (count != 0) count
    else {
      val strengthComparison = Integer.compare(strength(left, index), strength(right, index))
      if (strengthComparison != 0) strengthComparison
      else compareSymbols(left, right)
    }
  }

  private def compareSymbols(left: List[Symbol], right: List[Symbol]): Int =
    (left, right) match {
      case (Nil, Nil) => 0
      case (Nil, _)   => -1
      case (_, Nil)   => 1
      case (leftHead :: leftTail, rightHead :: rightTail) =>
        val comparison = leftHead.value.compareTo(rightHead.value)
        if (comparison != 0) comparison else compareSymbols(leftTail, rightTail)
    }

  private def extraTypeParams(constraints: List[Symbol], index: CatsIndex): List[String] = {
    val emitted = constraints.flatMap { constraint =>
      index.typeclasses.get(constraint).toList.flatMap { typeclass =>
        (1 until typeclass.typeParamCount).map { number =>
          if (number == 1) "E" else s"E$number"
        }
      }
    }
    emitted.distinct
  }
}
