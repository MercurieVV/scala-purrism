package fix.hkt

import scala.meta._

import scalafix.v1._

final class PreferHKTTypeclasses extends SemanticRule("PreferHKTTypeclasses") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val baseIndex = CatsIndex.load()
    val index = enrichIndexWithStdlib(baseIndex)
    val typeParamNames = List("G", "H", "I", "J", "K", "L", "M", "N", "O")

    val allDefinitions = doc.tree.collect { case defn: Defn.Def => defn }

    val patches = allDefinitions.flatMap { defn =>
      val results = UsageAnalyzer.analyze(defn, index, widenPublic = false)
      results.flatMap {
        case usage: UsageResult.Abstractable =>
          CapabilitySolver.solve(usage.ops, index, maxConstraints = 3) match {
            case Right(solution) =>
              val typeParamName = HktRewriter
                .freshTypeParamName(defn, typeParamNames)
                .getOrElse("G")
              Some(HktRewriter.rewrite(usage, solution, index, typeParamName))
            case Left(_) =>
              None
          }
        case _: UsageResult.Declined =>
          None
      }
    }

    patches.asPatch
  }

  private def enrichIndexWithStdlib(index: CatsIndex)(implicit
      doc: SemanticDocument
  ): CatsIndex = {
    val listCapabilities = List(
      Capability(
        Symbol("cats/Functor#"),
        Symbol("scala/collection/immutable/List#map()."),
        Symbol("cats/Functor#map()."),
        KindShape.Unary,
        derived = false,
        arity = 2
      ),
      Capability(
        Symbol("cats/FlatMap#"),
        Symbol("scala/collection/immutable/List#flatMap()."),
        Symbol("cats/FlatMap#flatMap()."),
        KindShape.Unary,
        derived = false,
        arity = 2
      ),
      Capability(
        Symbol("cats/Foldable#"),
        Symbol("scala/collection/immutable/List#foldMap()."),
        Symbol("cats/Foldable#foldMap()."),
        KindShape.Unary,
        derived = false,
        arity = 2
      ),
      Capability(
        Symbol("cats/Traverse#"),
        Symbol("scala/collection/immutable/List#traverse()."),
        Symbol("cats/Traverse#traverse()."),
        KindShape.Unary,
        derived = false,
        arity = 2
      ),
      Capability(
        Symbol("cats/Apply#"),
        Symbol("scala/collection/immutable/List#map2()."),
        Symbol("cats/Apply#map2()."),
        KindShape.Unary,
        derived = false,
        arity = 3
      )
    )

    val allCapabilities =
      index.capabilities.values.flatten.toList ++ listCapabilities
    val groupedByTypeclass = allCapabilities.groupBy(_.typeclass)

    new CatsIndex(
      index.typeclasses,
      groupedByTypeclass,
      index.syntax
    )
  }
}
