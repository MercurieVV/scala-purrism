package fix

import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.v1._

/** Pre-0.9.0 names for the three signature-widening rules, kept resolvable
  * under their old `rules = [...]` entry and old config block so an existing
  * `.scalafix.conf` does not break outright the moment this artifact is
  * upgraded.
  *
  * Each class here is composition, not inheritance: `SemanticRule`'s `name` is
  * fixed by the string handed to its own constructor, so a subclass of e.g.
  * [[PreferPolymorphicTypeclasses]] could not answer to the old name -- it
  * would just be the new rule under an alias. These instead hold a delegate
  * built from the old config, translated to the new config shape, and forward
  * `fix` to it.
  *
  * Slated for removal once the old names have had a deprecation cycle; see
  * `docs/RULES.md`.
  */
@deprecated("Renamed to PreferPolymorphicTypeclasses", "0.9.0")
final case class PreferHKTTypeclassesConfig(
    rewrite: Boolean = true,
    widenPublic: Boolean = false,
    maxConstraints: Int = 2,
    containers: List[String] =
      List("List", "Seq", "Vector", "IndexedSeq", "LazyList")
) {
  private[fix] def toCurrent: PreferPolymorphicTypeclassesConfig =
    PreferPolymorphicTypeclassesConfig(
      rewrite,
      widenPublic,
      maxConstraints,
      containers
    )
}

@deprecated("Renamed to PreferPolymorphicTypeclassesConfig", "0.9.0")
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

@deprecated("Renamed to PreferPolymorphicTypeclasses", "0.9.0")
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

  private val delegate =
    new PreferPolymorphicTypeclasses(config.toCurrent, crossFile, classpath)

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

  override def fix(implicit doc: SemanticDocument): Patch = delegate.fix
}

@deprecated("Renamed to PreferPolymorphicCollections", "0.9.0")
final case class PreferContainerTypeclassesConfig(
    rewrite: Boolean = true,
    widenPublic: Boolean = false,
    maxConstraints: Int = 2,
    containers: List[String] =
      List("List", "Seq", "Vector", "IndexedSeq", "LazyList")
) {
  private[fix] def toCurrent: PreferPolymorphicCollectionsConfig =
    PreferPolymorphicCollectionsConfig(
      rewrite,
      widenPublic,
      maxConstraints,
      containers
    )
}

@deprecated("Renamed to PreferPolymorphicCollectionsConfig", "0.9.0")
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

@deprecated("Renamed to PreferPolymorphicCollections", "0.9.0")
final class PreferContainerTypeclasses(
    config: PreferContainerTypeclassesConfig,
    crossFile: CrossFileConfig,
    classpath: List[java.nio.file.Path]
) extends SemanticRule("PreferContainerTypeclasses") {

  def this(config: PreferContainerTypeclassesConfig) =
    this(config, CrossFileConfig.default, Nil)

  def this() = this(PreferContainerTypeclassesConfig.default)

  private val delegate =
    new PreferPolymorphicCollections(config.toCurrent, crossFile, classpath)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PreferContainerTypeclasses")(
        PreferContainerTypeclassesConfig.default
      )
      .product(
        configuration.conf
          .getOrElse("PreferContainerTypeclasses")(CrossFileConfig.default)
      )
      .map { case (config, crossFile) =>
        new PreferContainerTypeclasses(
          config,
          crossFile,
          configuration.scalacClasspath.map(_.toNIO)
        )
      }

  override def fix(implicit doc: SemanticDocument): Patch = delegate.fix
}

@deprecated("Renamed to PreferPolymorphicCollectionOps", "0.9.0")
final case class PreferElementTypeclassesConfig(
    rewrite: Boolean = true,
    widenPublic: Boolean = false,
    maxConstraints: Int = 2,
    containers: List[String] =
      List("List", "Seq", "Vector", "IndexedSeq", "LazyList")
) {
  private[fix] def toCurrent: PreferPolymorphicCollectionOpsConfig =
    PreferPolymorphicCollectionOpsConfig(
      rewrite,
      widenPublic,
      maxConstraints,
      containers
    )
}

@deprecated("Renamed to PreferPolymorphicCollectionOpsConfig", "0.9.0")
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

@deprecated("Renamed to PreferPolymorphicCollectionOps", "0.9.0")
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

  private val delegate = new PreferPolymorphicCollectionOps(
    config.toCurrent,
    crossFile,
    elementTypes,
    classpath
  )

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

  override def fix(implicit doc: SemanticDocument): Patch = delegate.fix
}
