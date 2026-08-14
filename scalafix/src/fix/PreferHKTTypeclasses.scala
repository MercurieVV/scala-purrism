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

final case class PreferHKTTypeclassesConfig(
    rewrite: Boolean = true,
    widenPublic: Boolean = false,
    maxConstraints: Int = 2,
    /** Constructors this rule leaves to `PreferContainerTypeclasses`.
      *
      * Both rules widen a unary constructor to a type parameter, and run
      * together they widen the same one twice --
      * `names[S[_]: Functor][G[_]: Functor](users: SG[String])`, which is not a
      * signature. The collections are the container rule's subject: it names
      * the parameter `S`, reports positional access, and is configured with
      * this same list. Set this to `[]` to widen collections with this rule
      * where `PreferContainerTypeclasses` is not enabled.
      */
    containers: List[String] =
      List("List", "Seq", "Vector", "IndexedSeq", "LazyList")
)

object PreferHKTTypeclassesConfig {
  val default: PreferHKTTypeclassesConfig = PreferHKTTypeclassesConfig()

  implicit val decoder: ConfDecoder[PreferHKTTypeclassesConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("rewrite")(default.rewrite)
        .product(conf.getOrElse("widenPublic")(default.widenPublic))
        .product(conf.getOrElse("maxConstraints")(default.maxConstraints))
        .product(conf.getOrElse("containers")(default.containers))
        .map { case (((rewrite, widenPublic), maxConstraints), containers) =>
          PreferHKTTypeclassesConfig(
            rewrite,
            widenPublic,
            maxConstraints,
            containers
          )
        }
    }
}

/** The shape this rule's configuration had before it was wired to the `fix.hkt`
  * engine. Retained so the published constructor
  * `PreferHKTTypeclasses(PreferHKTConfig)` keeps resolving; new configuration
  * goes on [[PreferHKTTypeclassesConfig]].
  */
final case class PreferHKTConfig(
    widenPublic: Boolean = false
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

/** Retained for binary compatibility; declines now carry the analyzer's own
  * reason through [[HKTDeclineDiagnostic]].
  */
final case class DeclineHKTAbstractionDiagnostic(
    override val position: scala.meta.inputs.Position
) extends Diagnostic {
  override def message: String =
    "This function is a candidate for HKT abstraction but was declined."
  override def severity: LintSeverity = LintSeverity.Warning
}

final case class HKTDeclineDiagnostic(
    override val position: scala.meta.inputs.Position,
    reason: DeclineReason
) extends Diagnostic {
  override def message: String = reason.message
  override def severity: LintSeverity = LintSeverity.Warning
}

/** Widens a concrete type constructor in a signature to a type parameter `G[_]`
  * carrying the weakest Cats typeclass the body actually uses.
  *
  * {{{
  * private def names(us: List[User]): List[String] = us.map(_.name)
  * private def names[G[_]: Functor](us: G[User]): G[String] = us.map(_.name)
  * }}}
  *
  * The rule is a wiring of the shared capability engine -- `UsageAnalyzer`
  * resolves each body call to the Cats capability it needs, `CapabilitySolver`
  * picks the weakest constraint set covering them, `HktRewriter` renders the
  * signature and the imports. `PreferContainerTypeclasses` is the same engine
  * pointed at collections only; this rule takes any unary constructor the index
  * knows -- `Eval`, `Show`, `Semigroup`, `Option`, `List`.
  *
  * Only `KindShape.Unary` targets are widened. `Star` targets (abstracting an
  * *element* to `A: Monoid`) belong to `PreferElementTypeclasses`, and `Binary`
  * ones are out of v1 by `docs/design/PreferHKTTypeclasses.md` item 6: Scala 3
  * does not reliably infer `[X] =>> Either[E, X]` at the call sites, so the
  * rewrite would compile at the definition and break at every caller.
  *
  * The body is never rewritten. A definition whose body names the concrete
  * constructor in a way the signature widening would invalidate -- an operation
  * outside the solved constraints, or the value handed to a signature that
  * names the constructor -- is declined by `ContainerFlow` instead of being
  * half-abstracted.
  */
final class PreferHKTTypeclasses(
    config: PreferHKTTypeclassesConfig,
    crossFile: CrossFileConfig,
    classpath: List[java.nio.file.Path]
) extends SemanticRule("PreferHKTTypeclasses") {

  def this(config: PreferHKTTypeclassesConfig) =
    this(config, CrossFileConfig.default, Nil)

  def this() = this(PreferHKTTypeclassesConfig.default)

  def this(legacy: PreferHKTConfig) =
    this(PreferHKTTypeclassesConfig(widenPublic = legacy.widenPublic))

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferHKTTypeclasses")(PreferHKTTypeclassesConfig.default)
      .product(
        configuration.conf
          .getOrElse("PreferHKTTypeclasses")(CrossFileConfig.default)
      )
      .map { case (config, crossFile) =>
        new PreferHKTTypeclasses(
          config,
          crossFile,
          configuration.scalacClasspath.map(_.toNIO)
        )
      }

  private lazy val index: CatsIndex = CatsIndex.load()

  /** The project-wide view of what widening would do to call sites; empty
    * unless `crossFile` is on.
    *
    * The subject is the wildcard, because this rule's subject is every unary
    * constructor the index knows -- and the index is what the wildcard
    * consults. It read `Nil` before, which the scope takes as a *subject* list
    * rather than an exclusion list, so no definition was ever a candidate and
    * the scan answered "nothing to repair" for a rule that had been asked to
    * look.
    */
  private lazy val scope: WidenScope =
    WidenScope.forRun(
      crossFile,
      classpath,
      List(ContainerNames.Wildcard),
      config.widenPublic,
      symbol => index.knowsConstructor(Symbol(symbol))
    )

  override def fix(implicit doc: SemanticDocument): Patch = {
    val suppression = Suppression.forDocument
    val handedOver = ContainerCallSites.valueReferences(doc.tree)

    doc.tree.collect {
      case defn: Defn.Def
          if !suppression.suppresses(defn) && !alreadyAbstracted(defn) =>
        patchFor(defn, handedOver)
    }.asPatch + callSiteRepairs
  }

  /** The other half of a widening: a call that named its type arguments has to
    * stop naming them, because the def it calls has grown one it does not
    * mention. See [[WidenScope]].
    */
  private def callSiteRepairs(implicit doc: SemanticDocument): Patch =
    if (scope.isEmpty) Patch.empty
    else
      doc.tree.collect {
        case applyType: Term.ApplyType
            if scope.repairs(applyType.fun.symbol.value) =>
          Patch.removeTokens(applyType.targClause.tokens)
      }.asPatch

  /** One definition's verdict: at most one rewrite, otherwise at most one
    * diagnostic.
    *
    * `UsageAnalyzer` reports per *target* -- every concrete constructor in the
    * signature -- so a definition mentioning two of them yields two results. A
    * def gets one type parameter, and a reader gets one reason.
    */
  private def patchFor(
      defn: Defn.Def,
      handedOver: Set[String]
  )(implicit doc: SemanticDocument): Patch = {
    // Analysed as if every definition were widenable, and the visibility
    // verdict applied afterwards. Asking the analyzer to decide it reports
    // `PublicBoundary` for every public definition that merely *mentions* a
    // concrete type -- `def apply(value: Long): TimeStamp` -- which is a
    // warning on most of a codebase and a fact about none of it. The boundary
    // is worth reporting only where there was something to widen.
    val results = UsageAnalyzer.analyze(defn, index, widenPublic = true)

    solved(results) match {
      case Some((usage, solution)) =>
        if (scope.vetoes(defn.symbol.value))
          // Some call site names this def's type arguments, and inference
          // cannot replace them, so it cannot take another type parameter.
          lint(
            defn.name.pos,
            DeclineReason.UnsafeBody(
              s"`${defn.name.value}` is called with explicit type arguments " +
                "that inference cannot replace"
            )
          )
        else if (!UsageAnalyzer.isWidenable(defn, config.widenPublic))
          lint(defn.name.pos, DeclineReason.PublicBoundary(defn.name.value))
        else if (handedOver.contains(defn.name.value))
          // A widened signature is source-compatible at every application
          // site, because the argument still infers the type parameter. `val g
          // = f` is the one shape that is not: a polymorphic method has no
          // monomorphic function type to eta-expand to.
          lint(
            defn.name.pos,
            DeclineReason.UnsafeBody(
              s"`${defn.name.value}` is handed over as a value"
            )
          )
        else if (!config.rewrite)
          lint(
            defn.name.pos,
            DeclineReason.UnsafeBody(
              "abstractable over " +
                solution.constraints.map(_.value).mkString(", ") +
                " (rewriting is off)"
            )
          )
        else
          HktRewriter
            .freshTypeParamName(usage.defn, TypeParamNames)
            .map(name =>
              HktRewriter.rewrite(usage, solution, index, name) +
                removeStaleImport(usage)
            )
            .getOrElse(
              lint(defn.name.pos, DeclineReason.NameConflict(TypeParamNames))
            )
      case None if hasKnownUnaryTarget(defn) =>
        results
          .collectFirst {
            case UsageResult.Declined(position, reason)
                if isReportable(reason) =>
              lint(position, reason)
          }
          .getOrElse(Patch.empty)
      case None => Patch.empty
    }
  }

  /** Whether the signature names a unary constructor the index has a theory of.
    *
    * The analyzer reports a body obstacle -- a `var`, a `throw`, an indexing
    * call -- against every concrete type in the signature, including ones no
    * Cats typeclass was ever going to cover. On a DSP codebase that means
    * `def sumPlanes(out: FloatBuffer, base: Int): Unit` is told its `var` stops
    * an abstraction that was never available. A decline is only worth printing
    * where widening was a possibility.
    */
  private def hasKnownUnaryTarget(
      defn: Defn.Def
  )(implicit doc: SemanticDocument): Boolean =
    signatureTypes(defn).exists {
      case applied: Type.Apply if applied.argClause.values.size == 1 =>
        val written = applied.tpe.symbol
        !written.isNone &&
        written.isGlobal &&
        !doc.info(written).exists(_.isTypeParameter) &&
        index.knowsConstructor(UsageAnalyzer.canonicalConstructor(written))
      case _ => false
    }

  /** Which declines are this rule's to report.
    *
    * `NoCapability` is not. The analyzer declines a definition on the first
    * call it cannot resolve *anywhere* in the body, and in real code that is
    * usually an element-level one -- `String#toInt`, a call to another method
    * of the same class -- which says nothing about the constructor being
    * abstracted. Reporting it turns the rule into a warning on every third
    * definition, so a body the index cannot account for is simply not widened.
    */
  private def isReportable(reason: DeclineReason): Boolean =
    reason match {
      case _: DeclineReason.NoCapability => false
      // Nor is `UnsupportedKind`. It is decided from the signature alone, so on
      // a real codebase it fires for every `Map[K, V]` and `Either[E, A]` a
      // definition happens to mention, whether or not the body treats it as a
      // functor. The gap it names is permanent for v1 and recorded in
      // `gaps.tsv`; repeating it per signature tells a reader nothing they can
      // act on.
      case _: DeclineReason.UnsupportedKind => false
      // Nor is `InheritedSignature`: an override's signature belongs to the
      // supertype, which is not an obstacle anyone can remove here.
      case _: DeclineReason.InheritedSignature => false
      case _                                   => true
    }

  /** The import of the constructor that was just abstracted away.
    *
    * `private def duplicate(e: Eval[Int])` becomes `duplicate[G[_]: Comonad]`,
    * and `import cats.Eval` then names nothing the file still mentions. Left
    * behind it is a warning under `-Wunused:imports`, which this repository --
    * and anything else running that flag with `-Werror` -- treats as an error,
    * so the rewrite would break the build it was meant to improve.
    */
  private def removeStaleImport(
      usage: UsageResult.Abstractable
  )(implicit doc: SemanticDocument): Patch = {
    val entities = widenedEntities(usage)
    if (constructorStillUsed(usage, entities)) Patch.empty
    else
      doc.tree
        .collect {
          case importee: Importee.Name
              if names(importee.name.symbol, entities) =>
            Patch.removeImportee(importee)
        }
        .foldLeft(Patch.empty)(_ + _)
  }

  /** The names the widened constructor answers to.
    *
    * Two of them, because a signature and its semantic type disagree about
    * aliases: `Semigroup[String]` is written `cats/package.Semigroup#` and
    * inferred `cats/kernel/Semigroup#`, and the import that has to go names the
    * first while the analyzer reports the second.
    */
  private def widenedEntities(
      usage: UsageResult.Abstractable
  )(implicit doc: SemanticDocument): Set[String] = {
    val written = usage.target match {
      case applied: Type.Apply => applied.tpe.symbol
      case target              => target.symbol
    }
    Set(usage.constructor, written).filter(!_.isNone).map(entityName)
  }

  /** Whether the widened constructor is named anywhere the rewrite leaves
    * standing: another definition, a body, or a nested position of this
    * definition's own signature -- `List[List[A]]` keeps its inner `List`.
    */
  private def constructorStillUsed(
      usage: UsageResult.Abstractable,
      entities: Set[String]
  )(implicit doc: SemanticDocument): Boolean = {
    val replaced = widenedHeads(usage, entities).map(_.pos).toSet
    doc.tree.collect {
      case tree: Type.Name
          if names(tree.symbol, entities) && !replaced.contains(tree.pos) =>
        tree
      case tree: Term.Name if names(tree.symbol, entities) => tree
    }.nonEmpty
  }

  /** The constructor heads `HktRewriter` replaces: the outermost application of
    * the widened constructor in each declared parameter type and in the result
    * type.
    */
  private def widenedHeads(
      usage: UsageResult.Abstractable,
      entities: Set[String]
  )(implicit doc: SemanticDocument): List[Type] = {
    def heads(tree: Tree): List[Type] =
      tree match {
        case applied: Type.Apply if names(applied.tpe.symbol, entities) =>
          List(applied.tpe)
        case _ => tree.children.flatMap(heads)
      }

    val roots = usage.defn.paramClauseGroups
      .flatMap(_.paramClauses)
      .flatMap(_.values)
      .flatMap(_.decltpe) ++ usage.defn.decltpe.toList
    roots.flatMap(heads)
  }

  /** Whether `symbol` is one of `entities`, class and companion alike:
    * `import cats.Eval` is the importee for both `cats/Eval#` and `cats/Eval.`.
    */
  private def names(symbol: Symbol, entities: Set[String]): Boolean =
    !symbol.isNone && entities.contains(entityName(symbol))

  private def entityName(symbol: Symbol): String =
    symbol.value.stripSuffix("#").stripSuffix(".")

  /** The first target that both solves to a non-empty constraint set and stays
    * abstract for the whole body.
    *
    * An empty constraint set means the body never touches the value through
    * that constructor at all: widening it then renders `def f[G[_]](x: G[A])`,
    * an unconstrained parameter the caller cannot instantiate from anything the
    * body does. That is not a decline worth reporting -- every signature
    * mentions constructors it merely passes through -- so it is skipped
    * silently and the next target is tried.
    */
  private def solved(
      results: List[UsageResult]
  ): Option[(UsageResult.Abstractable, CapabilitySolver.Solution)] =
    results.iterator
      .collect {
        case usage: UsageResult.Abstractable
            if isUnary(usage) && !isContainer(usage.constructor) =>
          usage
      }
      .flatMap { usage =>
        CapabilitySolver
          .solve(usage.ops, index, config.maxConstraints)
          .toOption
          .filter(_.constraints.nonEmpty)
          .filter(_ =>
            ContainerFlow.staysAbstract(usage, index.exitsConstructor)
          )
          .map(usage -> _)
      }
      .nextOption()

  /** Whether the constructor is one `PreferContainerTypeclasses` owns.
    *
    * `["*"]` cedes every constructor, which is the setting for a run where the
    * container rule is configured with the same wildcard and this rule is only
    * along for the declines.
    */
  private def isContainer(constructor: Symbol): Boolean =
    !constructor.isNone &&
      ContainerNames.matches(
        config.containers,
        constructor.value.stripSuffix("#").split('/').last.split('.').last
      )

  /** Whether the target is a one-argument application -- the `F[_]` this rule
    * abstracts, as opposed to a proper type or a binary constructor.
    */
  private def isUnary(usage: UsageResult.Abstractable): Boolean =
    usage.target match {
      case applied: Type.Apply => applied.argClause.values.size == 1
      case _                   => false
    }

  /** Whether this definition is already the output of this rule.
    *
    * A rewritten signature reads `G[User]`, whose head is a type parameter and
    * therefore not a target -- but the analyzer descends into the arguments,
    * where `User` is concrete. Nothing widens a `Star` target here, so the
    * second run is already a no-op; this states the invariant rather than
    * relying on that filter, and keeps a def whose constructor is already
    * abstract out of the diagnostic path too.
    */
  private def alreadyAbstracted(
      defn: Defn.Def
  )(implicit doc: SemanticDocument): Boolean = {
    val constrained = constrainedTypeParams(defn)
    signatureTypes(defn).exists {
      case applied: Type.Apply => constrained(applied.tpe.syntax)
      case _                   => false
    }
  }

  private def signatureTypes(defn: Defn.Def): List[Type] = {
    val roots = defn.paramClauseGroups
      .flatMap(_.paramClauses)
      .flatMap(_.values)
      .flatMap(_.decltpe) ++ defn.decltpe.toList
    roots.flatMap(root => root :: root.collect { case tpe: Type => tpe })
  }

  /** Type parameters visible from `defn` that already carry a Cats constraint,
    * by name -- from a context bound or from a `using` clause.
    */
  private def constrainedTypeParams(
      defn: Defn.Def
  )(implicit doc: SemanticDocument): Set[String] = {
    val owners = defn :: enclosingTrees(defn)
    val bound = owners.flatMap(typeParameters).filter { param =>
      contextBounds(param).exists(bound => isCatsTypeclass(bound))
    }
    val evidence = owners.flatMap(usingEvidenceArguments)
    bound.map(_.name.value).toSet ++ evidence
  }

  private def usingEvidenceArguments(tree: Tree)(implicit
      doc: SemanticDocument
  ): List[String] =
    parameterClauses(tree)
      .filter(_.mod.exists(_.is[Mod.Using]))
      .flatMap(_.values)
      .flatMap(_.decltpe)
      .collect {
        case applied: Type.Apply if isCatsTypeclass(applied.tpe) =>
          applied.argClause.values.headOption.map(_.syntax)
      }
      .flatten

  private def isCatsTypeclass(tpe: Type)(implicit
      doc: SemanticDocument
  ): Boolean = {
    val symbol = tpe match {
      case applied: Type.Apply => applied.tpe.symbol
      case other               => other.symbol
    }
    !symbol.isNone && index.typeclasses.contains(symbol)
  }

  private def parameterClauses(tree: Tree): List[Term.ParamClause] =
    tree match {
      case defn: Defn.Def  => defn.paramClauseGroups.flatMap(_.paramClauses)
      case cls: Defn.Class => cls.ctor.paramClauses.toList
      case traitDef: Defn.Trait => traitDef.ctor.paramClauses.toList
      case enumDef: Defn.Enum   => enumDef.ctor.paramClauses.toList
      case _                    => Nil
    }

  private def typeParameters(tree: Tree): List[Type.Param] =
    tree match {
      case defn: Defn.Def =>
        defn.paramClauseGroups.flatMap(_.tparamClause.values)
      case cls: Defn.Class      => cls.tparamClause.values
      case traitDef: Defn.Trait => traitDef.tparamClause.values
      case enumDef: Defn.Enum   => enumDef.tparamClause.values
      case _                    => Nil
    }

  private def contextBounds(param: Type.Param): List[Type] = {
    val bounds = param.productElement(3).asInstanceOf[Type.Bounds]
    bounds.productElement(2).asInstanceOf[List[Type]]
  }

  private def enclosingTrees(tree: Tree): List[Tree] =
    tree.parent match {
      case Some(parent) => parent :: enclosingTrees(parent)
      case None         => Nil
    }

  /** `G` first: it is what the fixtures and
    * `docs/design/PreferHKTTypeclasses.md` name the abstracted constructor, and
    * `F` is left alone because a file that already abstracts over an effect has
    * usually spent it.
    */
  private val TypeParamNames: List[String] = List("G", "H", "K")

  private def lint(
      position: scala.meta.inputs.Position,
      reason: DeclineReason
  )(implicit doc: SemanticDocument): Patch =
    Patch.lint(HKTDeclineDiagnostic(position, reason))
}
