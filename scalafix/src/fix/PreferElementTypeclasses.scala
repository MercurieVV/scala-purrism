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
    config: PreferElementTypeclassesConfig
) extends SemanticRule("PreferElementTypeclasses") {

  def this() = this(PreferElementTypeclassesConfig.default)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferElementTypeclasses")(
        PreferElementTypeclassesConfig.default
      )
      .map(new PreferElementTypeclasses(_))

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
                  !handedOver.contains(defn.name.value) =>
              rewrite(usage)
          }
          .asPatch
    }.asPatch
  }

  private def rewrite(
      usage: UsageResult.Abstractable
  )(implicit doc: SemanticDocument): Patch = {
    val renames = elementCalls(usage)
    if (renames.isEmpty || !config.rewrite) Patch.empty
    else if (!ContainerFlow.staysAbstract(usage)) Patch.empty
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

  /** Element types Cats ships `Show`, `Monoid` and `Order` instances for.
    *
    * Deliberately short and deliberately data: the failure mode of guessing
    * wrong is a file that does not compile, and every entry here is one Cats
    * documents. Grow it rather than widening the test.
    */
  private def instanceProvided(element: Type): Boolean =
    ProvidedElements.contains(element.syntax)

  private val ProvidedElements: Set[String] =
    Set(
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

  private val TypeParamNames: List[String] = List("S", "C", "G")

  private def isContainer(constructor: Symbol): Boolean =
    !constructor.isNone && config.containers.contains(simpleName(constructor))

  private def simpleName(symbol: Symbol): String =
    symbol.value.stripSuffix("#").split('/').last.split('.').last
}
