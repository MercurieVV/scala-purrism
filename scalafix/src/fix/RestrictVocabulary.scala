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
  override def categoryID: String = "typeWhitelist"
  override def message: String =
    s"names '$foundFqcn'; only types matching this file's configured scope/classes whitelist may be named here"
}

final case class BannedConstructDiagnostic(
    override val position: scala.meta.inputs.Position,
    constructClass: String,
    override val severity: scalafix.lint.LintSeverity
) extends Diagnostic {
  override def categoryID: String = "bannedConstruct"
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
  override def categoryID: String = "conversionBudget"
  override def message: String =
    s"requires $count distinct instantiations of '$typeclass', exceeding the configured max of $max"
}

final case class UnknownProfileDiagnostic(
    override val position: scala.meta.inputs.Position,
    reason: String
) extends Diagnostic {
  override def categoryID: String = "unknownProfile"
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Error
  override def message: String = reason
}

private object BudgetCollector {

  /** The nearest enclosing class/trait/object definition, for anchoring a
    * budget diagnostic on the declaration whose shape breaks (per
    * docs/RULES.md) and for grouping a typeclass requirement with its owner: a
    * trait/case-class and its companion object share a simple name, so grouping
    * by that name merges exactly that pair, per the spec, without merging two
    * unrelated classes in the same file.
    */
  private def enclosingDefn(
      tree: Tree
  ): Option[(String, scala.meta.inputs.Position)] = {
    @scala.annotation.tailrec
    def loop(t: Tree): Option[(String, scala.meta.inputs.Position)] = t match {
      case c: Defn.Class  => Some((c.name.value, c.pos))
      case t2: Defn.Trait => Some((t2.name.value, t2.pos))
      case o: Defn.Object => Some((o.name.value, o.pos))
      case other =>
        other.parent match {
          case Some(p) => loop(p)
          case None    => None
        }
    }
    loop(tree)
  }

  def typeArgTuples(tree: Tree, budgeted: Set[String])(implicit
      doc: SemanticDocument
  ): List[(String, List[String], String, scala.meta.inputs.Position)] =
    tree.collect {
      case app: Type.Apply if app.argClause.values.size == 2 =>
        val symbol = app.tpe.symbol
        if (symbol == Symbol.None) None
        else {
          val fqcn = PatternList.normalize(symbol.value)
          if (budgeted.contains(fqcn))
            enclosingDefn(app).map { case (ownerName, ownerPos) =>
              (fqcn, app.argClause.values.map(_.syntax), ownerName, ownerPos)
            }
          else None
        }
    }.flatten
}

final class RestrictVocabulary(config: VocabularyConfig)
    extends SemanticRule("RestrictVocabulary") {

  def this() = this(VocabularyConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("RestrictVocabulary")(VocabularyConfig.default)(using
        VocabularyConfig.decoder
      )
      .andThen { cfg =>
        val validated = for {
          scope <- PatternList.compile(cfg.scope)
          profile <- cfg.resolveProfile
          classes <- PatternList.compile(profile.classes)
          constructs <- ConstructMatcher.compile(profile.bannedConstructs)
        } yield (scope, classes, constructs)
        validated match {
          case Right(_)  => Configured.ok(new RestrictVocabulary(cfg))
          case Left(err) => Configured.error(err)
        }
      }

  override def fix(implicit doc: SemanticDocument): Patch =
    config.resolveProfile match {
      case Left(err) =>
        Patch.lint(UnknownProfileDiagnostic(doc.tree.pos, err))
      case Right(profile) =>
        val filePackage = ScopeCheck.filePackage(doc.tree)
        val scope =
          PatternList.compile(config.scope).getOrElse(PatternList.empty)
        if (!ScopeCheck.inScope(filePackage, scope)) Patch.empty
        else {
          val allowed =
            PatternList
              .compile(config.scope ++ profile.classes)
              .getOrElse(PatternList.empty)
          val constructs =
            ConstructMatcher
              .compile(profile.bannedConstructs)
              .getOrElse(ConstructMatcher.empty)

          val typeViolations = TypeWhitelistCheck.violations(doc.tree, allowed)
          val constructViolations = constructs.findAll(doc.tree)
          val budgetOverages = ConversionBudget.overages(
            BudgetCollector
              .typeArgTuples(doc.tree, profile.budgetedTypeclasses.toSet),
            profile.budgetedTypeclasses.toSet,
            profile.maxInstantiations
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
