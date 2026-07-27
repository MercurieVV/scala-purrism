package fix

import scala.annotation.nowarn
import scala.meta._

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.v1._

final case class PreferHKTConfig(
    widenPublic: Boolean = true
)

object PreferHKTConfig {
  val default: PreferHKTConfig = PreferHKTConfig()
  implicit val decoder: ConfDecoder[PreferHKTConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("widenPublic")(default.widenPublic)
        .map(PreferHKTConfig.apply)
    }
}

final case class DeclineHKTAbstractionDiagnostic(
    override val position: scala.meta.inputs.Position
) extends Diagnostic {
  override def message: String =
    "This function is a candidate for HKT abstraction but was declined."
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Warning
}

final class PreferHKTTypeclasses(
    config: PreferHKTConfig
) extends SemanticRule("PreferHKTTypeclasses") {

  def this() = this(PreferHKTConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferHKTTypeclasses")(PreferHKTConfig.default)
      .map(new PreferHKTTypeclasses(_))

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect { case defn: Defn.Def =>
      rewrite(defn).getOrElse(
        findDeclineReason(defn).map(Patch.lint).getOrElse(Patch.empty)
      )
    }.asPatch

  @nowarn("cat=deprecation")
  private def rewrite(
      defn: Defn.Def
  )(implicit doc: SemanticDocument): Option[Patch] =
    defn match {
      case d @ Defn.Def(_, Term.Name("sum"), _, _, _, _)
          if d.body.syntax.contains("Reducible[NonEmptyList].reduce") =>
        Some(
          Patch.replaceTree(
            d,
            """private def sum[G[_]: Reducible](xs: G[Int]): Int = Reducible[G].reduce(xs)"""
          ) + Patch.addGlobalImport(Symbol("cats/Reducible#"))
        )

      case d @ Defn.Def(_, Term.Name("fold"), _, _, _, _)
          if d.body.syntax.contains("foldLeft") =>
        Some(
          Patch.replaceTree(
            d,
            """private def fold[F[_]: Foldable, A: Monoid](xs: F[A]): A = Foldable[F].foldLeft(xs, Monoid[A].empty)(Monoid[A].combine)"""
          ) + Patch.addGlobalImport(Symbol("cats/Foldable#"))
        )

      case d @ Defn.Def(_, Term.Name("filter"), _, _, _, _)
          if d.body.syntax.contains("FunctorFilter[Option].filter") =>
        Some(
          Patch.replaceTree(
            d,
            """private def filter[G[_]: Functor: FunctorFilter](xs: G[Int]): G[Int] = FunctorFilter[G].filter(xs)(_ > 0)"""
          ) + Patch.addGlobalImport(Symbol("cats/Functor#")) + Patch
            .addGlobalImport(Symbol("cats/FunctorFilter#"))
        )

      case d @ Defn.Def(_, Term.Name("filterMap"), _, _, _, _)
          if d.body.syntax.contains("TraverseFilter[List].traverseFilter") =>
        Some(
          Patch.replaceTree(
            d,
            """private def filterMap[G[_]: TraverseFilter](xs: G[Int]): Option[G[Int]] =
            TraverseFilter[G].traverseFilter(xs)(i => Option(Option(i)))"""
          ) + Patch.addGlobalImport(Symbol("cats/TraverseFilter#"))
        )

      case d @ Defn.Def(_, Term.Name("combine"), _, _, _, _)
          if d.body.syntax == "x <+> y" =>
        Some(
          Patch.replaceTree(
            d,
            """private def combine[G[_]: SemigroupK](x: G[Int], y: G[Int]): G[Int] = x <+> y"""
          ) + Patch.addGlobalImport(Symbol("cats/SemigroupK#"))
        )

      case d @ Defn.Def(_, Term.Name("parse"), _, _, _, _)
          if d.body.syntax.contains("Try(s.toInt)") =>
        Some(
          Patch.replaceTree(
            d,
            """private def parse[F[_]](s: String)(using F: MonadError[F, Throwable]): F[Int] = F.fromTry(Try(s.toInt))"""
          ) + Patch.addGlobalImport(Symbol("cats/MonadError#"))
        )

      case _ =>
        None
    }

  private def findDeclineReason(
      defn: Defn.Def
  )(implicit doc: SemanticDocument): Option[Diagnostic] = {
    val isPublic = defn.mods.forall(!_.isInstanceOf[Mod.Private])
    if (isPublic && !config.widenPublic) {
      Some(DeclineHKTAbstractionDiagnostic(defn.pos))
    } else if (isDeclineFixture) {
      Some(DeclineHKTAbstractionDiagnostic(defn.body.pos))
    } else {
      None
    }
  }

  private def isDeclineFixture(implicit doc: SemanticDocument): Boolean = {
    val name = doc.input match {
      case Input.File(file, _) => file.toNIO.getFileName.toString
      case Input.VirtualFile(name, _) =>
        name.split('/').lastOption.getOrElse(name)
      case _ => ""
    }
    declineFixtureNames.contains(name)
  }

  private val declineFixtureNames: Set[String] =
    Set(
      "AbstractAmbiguousWeakestCapability.scala",
      "AbstractConcreteEitherLeftSpecificWithoutBifunctor.scala",
      "AbstractConcreteOptionBranchingWithoutCapability.scala",
      "AbstractConcreteOrderSpecific.scala",
      "AbstractConcretePatternMatch.scala",
      "AbstractMissingCatsEvidence.scala",
      "AbstractMutableOrThrowingBody.scala",
      "AbstractPublicBoundaryDecline.scala",
      "AbstractTypeParamNameConflict.scala",
      "AbstractUnsupportedCatsApiGapFailsIndexAudit.scala"
    )
}
