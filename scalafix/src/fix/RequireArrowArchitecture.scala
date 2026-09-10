package fix

import fix.vocabulary._
import metaconfig.Configured
import scala.meta._
import scalafix.v1._

final case class TypeWhitelistDiagnostic(
    override val position: scala.meta.inputs.Position,
    foundFqcn: String,
    override val severity: scalafix.lint.LintSeverity
) extends Diagnostic {
  override def message: String =
    s"names '$foundFqcn'; only types matching this file's configured scope/classes whitelist may be named here"
}

final case class BannedConstructDiagnostic(
    override val position: scala.meta.inputs.Position,
    constructClass: String,
    override val severity: scalafix.lint.LintSeverity
) extends Diagnostic {
  override def message: String =
    s"'$constructClass' is a banned construct in this scope"
}

final case class ConversionBudgetDiagnostic(
    override val position: scala.meta.inputs.Position,
    typeclass: String,
    count: Int,
    max: Int,
    override val severity: scalafix.lint.LintSeverity
) extends Diagnostic {
  override def message: String =
    s"requires $count distinct instantiations of '$typeclass', exceeding the configured max of $max"
}

private object BudgetCollector {
  def typeArgTuples(tree: Tree, budgeted: Set[String])(implicit
      doc: SemanticDocument
  ): List[(String, List[String])] =
    tree.collect {
      case app: Type.Apply if app.argClause.values.size == 2 =>
        val symbol = app.tpe.symbol
        if (symbol == Symbol.None) None
        else {
          val fqcn = PatternList.normalize(symbol.value)
          if (budgeted.contains(fqcn))
            Some(fqcn -> app.argClause.values.map(_.syntax))
          else None
        }
    }.flatten
}

final class RequireArrowArchitecture(config: VocabularyConfig)
    extends SemanticRule("RequireArrowArchitecture") {

  def this() = this(VocabularyConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("RequireArrowArchitecture")(VocabularyConfig.default)(using
        VocabularyConfig.decoder
      )
      .andThen { cfg =>
        val validated = for {
          scope <- PatternList.compile(cfg.scope)
          classes <- PatternList.compile(cfg.classes)
          constructs <- ConstructMatcher.compile(cfg.bannedConstructs)
        } yield (scope, classes, constructs)
        validated match {
          case Right(_)  => Configured.ok(new RequireArrowArchitecture(cfg))
          case Left(err) => Configured.error(err)
        }
      }

  override def fix(implicit doc: SemanticDocument): Patch = {
    val filePackage = ScopeCheck.filePackage(doc.tree)
    val scope = PatternList.compile(config.scope).getOrElse(PatternList.empty)
    if (!ScopeCheck.inScope(filePackage, scope)) Patch.empty
    else {
      val allowed =
        PatternList
          .compile(config.scope ++ config.classes)
          .getOrElse(PatternList.empty)
      val constructs =
        ConstructMatcher
          .compile(config.bannedConstructs)
          .getOrElse(ConstructMatcher.empty)

      val typeViolations = TypeWhitelistCheck.violations(doc.tree, allowed)
      val constructViolations = constructs.findAll(doc.tree)
      val budgetOverages = ConversionBudget.overages(
        doc.tree.pos,
        BudgetCollector
          .typeArgTuples(doc.tree, config.budgetedTypeclasses.toSet),
        config.budgetedTypeclasses.toSet,
        config.maxInstantiations
      )

      Patch.fromIterable(
        typeViolations.map(v =>
          Patch.lint(
            TypeWhitelistDiagnostic(
              v.position,
              v.foundFqcn,
              config.lintSeverity
            )
          )
        ) ++
          constructViolations.map(n =>
            Patch.lint(
              BannedConstructDiagnostic(
                n.pos,
                n.getClass.getName,
                config.lintSeverity
              )
            )
          ) ++
          budgetOverages.map(o =>
            Patch.lint(
              ConversionBudgetDiagnostic(
                o.typeAtOrClassPos,
                o.typeclass,
                o.count,
                o.max,
                config.lintSeverity
              )
            )
          )
      )
    }
  }
}
