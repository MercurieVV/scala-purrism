package fix.hkt

import scala.meta._

import scalafix.v1._

final class PreferHKTTypeclasses extends SemanticRule("PreferHKTTypeclasses") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val index = CatsIndex.load()
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
        case _ =>
          None
      }
    }

    patches.asPatch
  }
}
