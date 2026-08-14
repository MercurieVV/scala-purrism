package fix

import metaconfig.Configured
import scalafix.v1._

/** The three rules that answer one question -- what in this signature is more
  * general than it says -- run together.
  *
  * Each names what it abstracts: the *element* to `A: Monoid`, the *container*
  * to `S[_]: Foldable`, any other unary *constructor* to `G[_]: Comonad`. The
  * thing they have in common is the mechanism, which is what this umbrella is
  * named after: a concrete type in a signature becomes a type parameter with
  * the Cats constraints its body actually uses.
  *
  * {{{
  * private def names(users: List[String]): List[String] = users.map(_.toUpperCase)
  * private def names[S[_]: Functor](users: S[String]): S[String] = users.map(_.toUpperCase)
  * }}}
  *
  * They compose because their subjects are disjoint by construction:
  * `PreferHKTTypeclasses.containers` hands the collections to
  * `PreferContainerTypeclasses`, and an operation with an element rule --
  * `mkString`, `sum` -- is unresolvable to the plain container rule, which
  * therefore declines exactly the bodies `PreferElementTypeclasses` claims.
  *
  * Configuration is each sub-rule's own key, honoured here as it is when the
  * rule runs alone: `PreferContainerTypeclasses.widenPublic`,
  * `PreferContainerTypeclasses.crossFile`, `PreferHKTTypeclasses.containers`,
  * and so on.
  */
final class PreferTypeParameters(
    container: PreferContainerTypeclassesConfig,
    containerCrossFile: CrossFileConfig,
    element: PreferElementTypeclassesConfig,
    elementCrossFile: CrossFileConfig,
    elementTypes: ElementTypesConfig,
    hkt: PreferHKTTypeclassesConfig,
    hktCrossFile: CrossFileConfig,
    classpath: List[java.nio.file.Path]
) extends SemanticRule("PreferTypeParameters") {

  def this() =
    this(
      PreferContainerTypeclassesConfig.default,
      CrossFileConfig.default,
      PreferElementTypeclassesConfig.default,
      CrossFileConfig.default,
      ElementTypesConfig.default,
      PreferHKTTypeclassesConfig.default,
      CrossFileConfig.default,
      Nil
    )

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] = PreferTypeParameters.from(configuration)

  /** Built once, not per document: each sub-rule holds a lazily built index --
    * and, under `crossFile`, a scan of every source in the project.
    */
  private val rules: List[SemanticRule] =
    List(
      new PreferContainerTypeclasses(container, containerCrossFile, classpath),
      new PreferElementTypeclasses(
        element,
        elementCrossFile,
        elementTypes,
        classpath
      ),
      new PreferHKTTypeclasses(hkt, hktCrossFile, classpath)
    )

  override def fix(implicit doc: SemanticDocument): Patch =
    rules.map(_.fix).asPatch
}

object PreferTypeParameters {

  /** The configured rule, typed as itself.
    *
    * `withConfiguration` widens the result to `Rule`, which is all a rule
    * loaded by name needs; [[TypelevelPurrism]] holds this one as a field and
    * calls `fix` on it, so it needs the type back.
    */
  private[fix] def from(
      configuration: Configuration
  ): Configured[PreferTypeParameters] = {
    // Read one member's block at a time and keep the pieces in a local: a
    // `product` chain nests its result into a tuple one level deeper per
    // key, and the pattern match that unpicks seven of them is unreadable.
    val conf = configuration.conf
    val container =
      conf.getOrElse("PreferContainerTypeclasses")(
        PreferContainerTypeclassesConfig.default
      )
    val containerCrossFile =
      conf.getOrElse("PreferContainerTypeclasses")(CrossFileConfig.default)
    val element =
      conf.getOrElse("PreferElementTypeclasses")(
        PreferElementTypeclassesConfig.default
      )
    val elementCrossFile =
      conf.getOrElse("PreferElementTypeclasses")(CrossFileConfig.default)
    val elementTypes =
      conf.getOrElse("PreferElementTypeclasses")(ElementTypesConfig.default)
    val hkt =
      conf.getOrElse("PreferHKTTypeclasses")(
        PreferHKTTypeclassesConfig.default
      )
    val hktCrossFile =
      conf.getOrElse("PreferHKTTypeclasses")(CrossFileConfig.default)

    container
      .product(containerCrossFile)
      .product(element.product(elementCrossFile).product(elementTypes))
      .product(hkt.product(hktCrossFile))
      .map {
        case (
              (
                (container, containerCrossFile),
                ((element, elementCrossFile), elementTypes)
              ),
              (hkt, hktCrossFile)
            ) =>
          new PreferTypeParameters(
            container,
            containerCrossFile,
            element,
            elementCrossFile,
            elementTypes,
            hkt,
            hktCrossFile,
            // The classpath carries the SemanticDB payloads the cross-file
            // scope reads; dropping it would silently make the umbrella
            // weaker than the same rules run on their own.
            configuration.scalacClasspath.map(_.toNIO)
          )
      }
  }
}
