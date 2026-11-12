package fix.weakening

import fix.hkt.CatsIndex
import fix.hkt.CatsTypeclass
import scalafix.v1.Symbol

/** A typeclass as it is written in source: `symbol` is the typeclass itself,
  * `renderName`/`importPath` are the (possibly alias) name to emit.
  */
final case class Rendered(
    symbol: Symbol,
    renderName: String,
    importPath: String
)

enum Decision {
  case Keep
  case Replace(target: Rendered)
  case Ambiguous(options: List[Rendered])
}

object BoundSolver {

  /** Picks the weakest typeclass that still covers `required`.
    *
    * @param boundFixedArgs
    *   when the original bound is written through an alias (e.g. `MonadThrow`,
    *   passed as `MonadError`), the alias's fixed arguments; binary candidates
    *   must then render through an alias with the same arguments. For a unary
    *   bound it is `Nil`. A binary bound with `Nil` arguments is unknown, so
    *   the result is `Keep`.
    */
  def solve(
      bound: Symbol,
      required: Set[Symbol],
      index: CatsIndex,
      boundFixedArgs: List[String] = Nil
  ): Decision =
    index.typeclasses.get(bound) match {
      case None => Decision.Keep
      case Some(boundTc) =>
        val coversAll =
          required.forall(r => r == bound || index.isAncestor(r, bound))
        if (
          required.isEmpty || required.contains(bound) || !coversAll ||
          (isBinary(boundTc) && boundFixedArgs.isEmpty)
        ) Decision.Keep
        else {
          val candidates = (bound :: index.typeclasses.keysIterator
            .filter(index.isAncestor(_, bound))
            .toList).distinct.flatMap(sym =>
            for {
              tc <- index.typeclasses.get(sym)
              if tc.isPublic
              if required.forall(r => r == sym || index.isAncestor(r, sym))
              rendered <- render(tc, index, boundFixedArgs)
            } yield sym -> rendered
          )
          val minimal = candidates.filterNot { case (sym, _) =>
            candidates.exists { case (other, _) =>
              index.isAncestor(other, sym)
            }
          }
          minimal
            .sortBy { case (sym, _) => (index.depth(sym), sym.value) }
            .map(_._2) match {
            case Nil                                     => Decision.Keep
            case single :: Nil if single.symbol == bound => Decision.Keep
            case single :: Nil => Decision.Replace(single)
            case many          => Decision.Ambiguous(many)
          }
        }
    }

  private def isBinary(tc: CatsTypeclass): Boolean = tc.typeParamCount > 1

  /** A binary typeclass renders through its alias. Re-exports of one alias
    * (`cats.effect.MonadCancelThrow` and `cats.effect.kernel.MonadCancelThrow`)
    * share name and fixed arguments and count as one, imported through the
    * shortest path; genuinely different aliases leave it unrenderable.
    */
  private def render(
      tc: CatsTypeclass,
      index: CatsIndex,
      boundFixedArgs: List[String]
  ): Option[Rendered] =
    if (!isBinary(tc))
      Some(Rendered(tc.symbol, tc.renderName, tc.importPath))
    else {
      val aliases = index.aliasesOf(tc.symbol)
      aliases.map(alias => (alias.renderName, alias.fixedArgs)).distinct match {
        case (renderName, fixedArgs) :: Nil
            if boundFixedArgs.isEmpty || fixedArgs == boundFixedArgs =>
          aliases
            .map(_.importPath)
            .sortBy(path => (path.length, path))
            .headOption
            .map(Rendered(tc.symbol, renderName, _))
        case _ => None
      }
    }
}
