package fix

import scala.meta._

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.v1._

import fix.hkt.CapabilitySolver
import fix.hkt.CatsIndex
import fix.hkt.ElementRule
import fix.hkt.HktRewriter
import fix.hkt.UsageAnalyzer
import fix.hkt.UsageResult

final case class PreferElementTypeclassesConfig(
    rewrite: Boolean = true,
    widenPublic: Boolean = false,
    maxConstraints: Int = 2,
    containers: List[String] =
      List("List", "Seq", "Vector", "IndexedSeq", "LazyList")
)

object PreferElementTypeclassesConfig {
  val default: PreferElementTypeclassesConfig = PreferElementTypeclassesConfig()

  implicit val decoder: ConfDecoder[PreferElementTypeclassesConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("rewrite")(default.rewrite)
        .product(conf.getOrElse("widenPublic")(default.widenPublic))
        .product(conf.getOrElse("maxConstraints")(default.maxConstraints))
        .product(conf.getOrElse("containers")(default.containers))
        .map { case (((rewrite, widenPublic), maxConstraints), containers) =>
          PreferElementTypeclassesConfig(
            rewrite,
            widenPublic,
            maxConstraints,
            containers
          )
        }
    }
}

/** The concrete element types this rule is willing to assume an instance for.
  *
  * A separate configuration class rather than another field on
  * [[PreferElementTypeclassesConfig]] because that one is published: adding a
  * field to a case class changes `apply`, `copy` and the constructor, and MiMa
  * reads that as a break. Decoded from the same `PreferElementTypeclasses`
  * block, so it is one key to the user.
  *
  * An empty list turns the filter off: every element type is then taken to have
  * the instance the rewrite needs, which is what to set when the codebase ships
  * its own `Show` and `Monoid` instances. The default is only the ones Cats
  * itself documents, because the failure mode of guessing wrong is a file that
  * does not compile.
  */
final case class ElementTypesConfig(
    elements: List[String] = ElementTypesConfig.catsProvided
)

object ElementTypesConfig {

  /** Element types Cats ships `Show`, `Monoid` and `Order` instances for. */
  val catsProvided: List[String] =
    List(
      "String",
      "Int",
      "Long",
      "Short",
      "Byte",
      "Double",
      "Float",
      "Boolean",
      "Char",
      "BigInt",
      "BigDecimal"
    )

  val default: ElementTypesConfig = ElementTypesConfig()

  implicit val decoder: ConfDecoder[ElementTypesConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("elements")(default.elements)
        .map(ElementTypesConfig.apply)
    }
}

/** Widens a collection whose body uses an operation that Cats spells
  * differently and derives from a typeclass on the *element*.
  *
  * {{{
  * private def rendered(rows: List[String]): String =
  *   rows.mkString("[", ",", "]")
  *
  * private def rendered[S[_]: Foldable](rows: S[String]): String =
  *   rows.mkString_("[", ",", "]")
  * }}}
  *
  * Separate from [[PreferContainerTypeclasses]] because **it can change what
  * the program prints.** `mkString` renders each element with `toString`;
  * `mkString_` renders it with `Show`, and the two agree only where someone
  * made them agree. `sum` and `combineAll` are the same story for `Numeric`
  * against `Monoid`. Every other container rewrite leaves the body alone and is
  * a pure widening; these rewrite the call, so they are opt-in.
  *
  * Declines when the element's instance is not known to exist. For an element
  * that is a type parameter the rule adds the bound itself; for a concrete one
  * it only fires where Cats ships the instance, which is the list in
  * [[PreferElementTypeclasses.instanceProvided]].
  */
final class PreferElementTypeclasses(
    config: PreferElementTypeclassesConfig,
    crossFile: CrossFileConfig,
    elementTypes: ElementTypesConfig,
    classpath: List[java.nio.file.Path]
) extends SemanticRule("PreferElementTypeclasses") {

  def this(
      config: PreferElementTypeclassesConfig,
      crossFile: CrossFileConfig,
      classpath: List[java.nio.file.Path]
  ) = this(config, crossFile, ElementTypesConfig.default, classpath)

  def this(config: PreferElementTypeclassesConfig) =
    this(config, CrossFileConfig.default, Nil)

  def this() = this(PreferElementTypeclassesConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferElementTypeclasses")(
        PreferElementTypeclassesConfig.default
      )
      .product(
        configuration.conf
          .getOrElse("PreferElementTypeclasses")(CrossFileConfig.default)
      )
      .product(
        configuration.conf
          .getOrElse("PreferElementTypeclasses")(ElementTypesConfig.default)
      )
      .map { case ((config, crossFile), elementTypes) =>
        new PreferElementTypeclasses(
          config,
          crossFile,
          elementTypes,
          configuration.scalacClasspath.map(_.toNIO)
        )
      }

  /** This rule widens the container too, so the call sites that name their type
    * arguments are its problem as much as `PreferContainerTypeclasses`'. See
    * [[WidenScope]].
    */
  private lazy val scope: WidenScope =
    WidenScope.forRun(
      crossFile,
      classpath,
      config.containers,
      config.widenPublic,
      symbol => index.knowsConstructor(Symbol(symbol))
    )

  /** The index read so element operations resolve as ordinary capabilities; the
    * rename and the element bound are applied here afterwards.
    */
  private lazy val index: CatsIndex = CatsIndex.load().withElementRules

  override def fix(implicit doc: SemanticDocument): Patch = {
    val suppression = Suppression.forDocument
    val handedOver = ContainerCallSites.valueReferences(doc.tree)

    doc.tree.collect {
      case defn: Defn.Def if !suppression.suppresses(defn) =>
        UsageAnalyzer
          .analyze(defn, index, config.widenPublic)
          .collect {
            case usage: UsageResult.Abstractable
                if isContainer(usage.constructor) &&
                  !handedOver.contains(defn.name.value) &&
                  !scope.vetoes(defn.symbol.value) =>
              rewrite(usage)
          }
          .asPatch
    }.asPatch + callSiteRepairs
  }

  /** The other half of a widening: a call that named its type arguments has to
    * stop naming them, because the def it calls has grown one it does not
    * mention.
    */
  private def callSiteRepairs(implicit doc: SemanticDocument): Patch =
    if (scope.isEmpty) Patch.empty
    else
      doc.tree.collect {
        case applyType: Term.ApplyType
            if scope.repairs(applyType.fun.symbol.value) =>
          Patch.removeTokens(applyType.targClause.tokens)
      }.asPatch

  private def rewrite(
      usage: UsageResult.Abstractable
  )(implicit doc: SemanticDocument): Patch = {
    val renames = elementCalls(usage)
    if (renames.isEmpty || !config.rewrite) Patch.empty
    else if (!ContainerFlow.staysAbstract(usage, index.exitsConstructor))
      Patch.empty
    else
      CapabilitySolver.solve(usage.ops, index, config.maxConstraints) match {
        case Right(solution) if solution.constraints.nonEmpty =>
          elementBound(usage, renames) match {
            case Some(boundPatch) =>
              HktRewriter
                .freshTypeParamName(usage.defn, TypeParamNames)
                .map { name =>
                  HktRewriter.rewrite(usage, solution, index, name) +
                    renames.map(renameCall).asPatch +
                    boundPatch
                }
                .getOrElse(Patch.empty)
            case None => Patch.empty
          }
        case _ => Patch.empty
      }
  }

  /** `xs.mkString(...)` -> `xs.mkString_(...)`, anchored on the method name. */
  private def renameCall(call: (Term.Name, ElementRule)): Patch =
    Patch.replaceTree(call._1, call._2.renameTo)

  /** The calls in this definition that an element rule covers. */
  private def elementCalls(
      usage: UsageResult.Abstractable
  )(implicit doc: SemanticDocument): List[(Term.Name, ElementRule)] =
    usage.defn.body.collect {
      case Term.Select(_, name) if !name.symbol.isNone =>
        index.resolveElement(name.symbol).map(name -> _)
    }.flatten

  /** The bound the element needs, and where to put it.
    *
    * A type-parameter element gets the bound added to it. A concrete element
    * gets nothing added -- the instance has to already exist, and this only
    * fires for the ones Cats ships, because a rewrite that assumes `Show[Foo]`
    * and is wrong does not compile.
    */
  private def elementBound(
      usage: UsageResult.Abstractable,
      renames: List[(Term.Name, ElementRule)]
  )(implicit doc: SemanticDocument): Option[Patch] = {
    val constraints = renames.map(_._2.elementConstraint).distinct
    val rendered = constraints.flatMap(index.typeclasses.get)
    if (rendered.size != constraints.size) None
    else
      elementParam(usage) match {
        case Some(param) =>
          // A `using` clause rather than a context bound on the parameter:
          // `HktRewriter` anchors the container's own type parameter on that
          // same token, and two patches on one anchor concatenate in an order
          // neither of them chooses -- which put `Show` on the container.
          val clause = rendered
            .map(tc => s"${tc.renderName}[${param.name.value}]")
            .mkString("(using ", ", ", ")")
          val anchor = usage.defn.paramClauseGroups
            .flatMap(_.paramClauses)
            .lastOption
          Some(
            anchor
              .map(Patch.addRight(_, clause))
              .getOrElse(Patch.addRight(usage.defn.name, clause)) +
              rendered
                .map(tc => Patch.addGlobalImport(Symbol(tc.symbol.value)))
                .asPatch
          )
        case None =>
          // A concrete element needs no declaration at all -- the instance is
          // already there, or the rewrite would not have been allowed.
          Option.when(instanceProvided(usage.elementType))(Patch.empty)
      }
  }

  /** The definition's own type parameter that the element names, if any. */
  private def elementParam(
      usage: UsageResult.Abstractable
  ): Option[Type.Param] =
    usage.defn.paramClauseGroups
      .flatMap(_.tparamClause.values)
      .find(param => param.name.value == usage.elementType.syntax)

  /** Whether the element's instance can be assumed to exist.
    *
    * The list is [[ElementTypesConfig]]'s and defaults to what Cats itself
    * ships; empty means the codebase vouches for every element type.
    */
  private def instanceProvided(element: Type): Boolean =
    ProvidedElements.isEmpty || ProvidedElements.contains(element.syntax)

  private val ProvidedElements: Set[String] = elementTypes.elements.toSet

  private val TypeParamNames: List[String] = List("S", "C", "G")

  private def isContainer(constructor: Symbol): Boolean =
    !constructor.isNone &&
      (if (ContainerNames.isWildcard(config.containers))
         index.knowsConstructor(constructor)
       else config.containers.contains(simpleName(constructor)))

  private def simpleName(symbol: Symbol): String =
    symbol.value.stripSuffix("#").split('/').last.split('.').last
}
