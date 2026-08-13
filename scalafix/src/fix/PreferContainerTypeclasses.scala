package fix

import scala.meta._

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.lint.LintSeverity
import scalafix.v1._

import fix.hkt.CapabilitySolver
import fix.hkt.CatsIndex
import fix.hkt.DeclineReason
import fix.hkt.HktRewriter
import fix.hkt.UsageAnalyzer
import fix.hkt.UsageResult

final case class PreferContainerTypeclassesConfig(
    rewrite: Boolean = true,
    widenPublic: Boolean = false,
    maxConstraints: Int = 2,
    containers: List[String] =
      List("List", "Seq", "Vector", "IndexedSeq", "LazyList")
)

object PreferContainerTypeclassesConfig {
  val default: PreferContainerTypeclassesConfig =
    PreferContainerTypeclassesConfig()

  implicit val decoder: ConfDecoder[PreferContainerTypeclassesConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("rewrite")(default.rewrite)
        .product(conf.getOrElse("widenPublic")(default.widenPublic))
        .product(conf.getOrElse("maxConstraints")(default.maxConstraints))
        .product(conf.getOrElse("containers")(default.containers))
        .map { case (((rewrite, widenPublic), maxConstraints), containers) =>
          PreferContainerTypeclassesConfig(
            rewrite,
            widenPublic,
            maxConstraints,
            containers
          )
        }
    }
}

final case class ContainerAbstractionDiagnostic(
    override val position: scala.meta.inputs.Position,
    override val message: String
) extends Diagnostic {
  override def severity: LintSeverity = LintSeverity.Warning
}

/** Widens a concrete collection in a signature to the weakest Cats typeclass
  * the body actually uses.
  *
  * {{{
  * private def render(xs: Seq[Series]): Vector[Column]
  * private def render[S[_]: Foldable](xs: S[Series]): Vector[Column]
  * }}}
  *
  * The interesting question is not which typeclass to name but when *not* to: a
  * body that reaches an element by position -- `xs(i)`, `.indices`, `.head` --
  * is not expressing `Foldable`, it is expressing random access, and Cats has
  * no typeclass for that. `UsageAnalyzer` already answers this
  * (`DeclineReason.OrderOrIndexSpecific`), which is why this rule is a wiring
  * of the existing capability engine rather than a second copy of it.
  *
  * Default `widenPublic = false`. A widened signature keeps every ordinary call
  * site compiling -- `f(myList)` still infers `S = List` -- but a def handed
  * over as a *value* cannot be, because a polymorphic method does not
  * eta-expand to a monomorphic function type. For a private or local def that
  * question is answerable from the file scalafix was handed; for a public one
  * it is not, and `docs/RULES.md` forbids deciding it per file.
  */
final class PreferContainerTypeclasses(
    config: PreferContainerTypeclassesConfig
) extends SemanticRule("PreferContainerTypeclasses") {

  def this() = this(PreferContainerTypeclassesConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferContainerTypeclasses")(
        PreferContainerTypeclassesConfig.default
      )
      .map(new PreferContainerTypeclasses(_))

  private lazy val index: CatsIndex = CatsIndex.load()

  override def fix(implicit doc: SemanticDocument): Patch = {
    val suppression = Suppression.forDocument
    val handedOver = ContainerCallSites.valueReferences(doc.tree)

    doc.tree.collect {
      case defn: Defn.Def if !suppression.suppresses(defn) =>
        UsageAnalyzer
          .analyze(defn, index, config.widenPublic)
          .map(result => patchFor(defn, result, handedOver))
          .asPatch
    }.asPatch
  }

  private def patchFor(
      defn: Defn.Def,
      result: UsageResult,
      handedOver: Set[String]
  )(implicit doc: SemanticDocument): Patch =
    result match {
      case usage: UsageResult.Abstractable
          if isContainer(usage.constructor) && isParameter(
            defn,
            usage.target
          ) =>
        if (handedOver.contains(defn.name.value))
          lint(
            defn.name.pos,
            s"`${defn.name.value}` is handed over as a value, so widening it " +
              "to a type parameter would stop that reference compiling."
          )
        else rewrite(usage)
      case UsageResult.Declined(position, reason)
          if mentionsContainer(reason) =>
        lint(position, reason.message)
      case _ =>
        Patch.empty
    }

  private def rewrite(
      usage: UsageResult.Abstractable
  )(implicit doc: SemanticDocument): Patch =
    CapabilitySolver.solve(usage.ops, index, config.maxConstraints) match {
      // An empty constraint set means the body never touches the collection as
      // a collection. Widening it then produces `def f[S[_]](xs: S[A])(using )`
      // -- an unconstrained type parameter and an empty using clause, which is
      // not a weaker signature but an uncompilable one.
      case Right(solution) if solution.constraints.isEmpty =>
        lint(
          usage.defn.name.pos,
          "no Cats capability covers this container's use; nothing to widen to"
        )
      // `UsageAnalyzer` reports the ops it could map to a capability. It does
      // not report that it dropped one, so a body mixing `map` with `filter`
      // solves to `Functor` and loses the `filter`, and a body ending in
      // `mkString` solves to `Functor` over a value that no longer has one.
      // Both compile as `Vector` and neither compiles as `S`.
      case Right(solution) if !ContainerFlow.staysAbstract(usage) =>
        lint(
          usage.defn.name.pos,
          "the container does not stay abstract: an operation on it is not " +
            "covered by " + solution.constraints.map(_.value).mkString(", ") +
            ", or its value is passed to a signature that names a container"
        )
      case Right(solution) if config.rewrite =>
        HktRewriter
          .freshTypeParamName(usage.defn, TypeParamNames)
          .map(name => HktRewriter.rewrite(usage, solution, index, name))
          .getOrElse(
            lint(
              usage.defn.name.pos,
              DeclineReason.NameConflict(TypeParamNames).message
            )
          )
      case Right(solution) =>
        lint(
          usage.defn.name.pos,
          "container is abstractable over " +
            solution.constraints.map(_.value).mkString(", ") +
            " (rewriting is off)"
        )
      case Left(reason) =>
        lint(usage.defn.name.pos, reason.message)
    }

  /** Named for the container they stand for rather than for an effect: `S` is
    * what `Phrase.steps` and `Arrangement` already call theirs.
    */
  private val TypeParamNames: List[String] = List("S", "C", "G")

  /** Whether the widened type is one the caller passes in.
    *
    * `UsageAnalyzer` offers every concrete constructor in the signature,
    * including one that appears only in the result. Widening that is not an
    * abstraction: `private def readTrace(p: Path): Vector[Chunk]` becomes
    * `readTrace[S[_]]: S[Chunk]` and the body still returns a `Vector`. A
    * container is only abstractable when the caller chooses it.
    */
  private def isParameter(defn: Defn.Def, target: Type): Boolean =
    defn.paramClauseGroups
      .flatMap(_.paramClauses)
      .flatMap(_.values)
      .flatMap(_.decltpe)
      .exists(declared =>
        declared.pos.start <= target.pos.start &&
          target.pos.end <= declared.pos.end
      )

  private def isContainer(constructor: Symbol): Boolean =
    !constructor.isNone &&
      config.containers.contains(simpleName(constructor))

  /** A decline is only this rule's to report when it is about a container.
    *
    * `UsageAnalyzer` runs over every concrete constructor in the signature, so
    * without this filter a declined `Option` or `IO` would be reported by the
    * container rule, which has nothing to say about either.
    */
  private def mentionsContainer(reason: DeclineReason): Boolean =
    reason match {
      case _: DeclineReason.OrderOrIndexSpecific => true
      case _                                     => false
    }

  private def simpleName(symbol: Symbol): String =
    symbol.value.stripSuffix("#").split('/').last.split('.').last

  private def lint(position: scala.meta.inputs.Position, message: String)(
      implicit doc: SemanticDocument
  ): Patch =
    Patch.lint(ContainerAbstractionDiagnostic(position, message))
}

/** Which definitions in a file are used as values rather than called.
  *
  * A widened signature is source-compatible at every application site: `f(xs)`
  * infers the new type parameter from the argument it was already passing.
  * `val g = f` is different -- there is no monomorphic function type for a
  * polymorphic method -- and that is the one shape that must veto the rewrite.
  */
private[fix] object ContainerCallSites {

  def valueReferences(tree: Tree): Set[String] =
    tree.collect {
      case name: Term.Name if isValuePosition(name) => name.value
    }.toSet

  private def isValuePosition(name: Term.Name): Boolean =
    name.parent match {
      // The callee of an application is applied, not handed over.
      case Some(parent: Term.Apply)     => !(parent.fun eq name)
      case Some(parent: Term.ApplyType) => !(parent.fun eq name)
      case Some(parent: Term.Select)    => !(parent.name eq name)
      // A definition's own name is the declaration, not a reference to it.
      case Some(parent: Defn.Def)    => !(parent.name eq name)
      case Some(parent: Defn.Val)    => !parent.pats.exists(_ eq name)
      case Some(parent: Defn.Var)    => !parent.pats.exists(_ eq name)
      case Some(_: Term.Param)       => false
      case Some(_: Term.ParamClause) => false
      case Some(_: Type.Param)       => false
      case Some(_)                   => true
      case None                      => false
    }
}
