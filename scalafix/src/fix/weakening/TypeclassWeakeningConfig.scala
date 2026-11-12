package fix.weakening

import java.nio.file.Path
import java.nio.file.Paths

import fix.hkt.tasty.CacheConfig
import metaconfig.ConfDecoder

/** `TypeclassWeakening { cacheDir = ..., debug = ... }`.
  *
  * `cacheDir` is where per-jar TASTy index rows are cached; a leading `~` is
  * the user's home and `""` disables the disk cache. `debug` logs the index
  * source, the jars scanned and the time spent to stderr.
  */
final case class TypeclassWeakeningConfig(
    cacheDir: String = TypeclassWeakeningConfig.DefaultCacheDir,
    debug: Boolean = false
) {

  private[fix] def cacheConfig: CacheConfig =
    CacheConfig(TypeclassWeakeningConfig.resolve(cacheDir), debug)
}

object TypeclassWeakeningConfig {

  val DefaultCacheDir: String = "~/.cache/scala-purrism/tasty-index"

  val default: TypeclassWeakeningConfig = TypeclassWeakeningConfig()

  private[weakening] def resolve(cacheDir: String): Option[Path] =
    cacheDir.trim match {
      case "" => None
      case "~" =>
        Option(System.getProperty("user.home")).map(Paths.get(_))
      case home if home.startsWith("~/") =>
        Option(System.getProperty("user.home"))
          .map(Paths.get(_).resolve(home.stripPrefix("~/")))
      case other => Some(Paths.get(other))
    }

  implicit val decoder: ConfDecoder[TypeclassWeakeningConfig] =
    ConfDecoder.from { conf =>
      conf
        .getOrElse("cacheDir")(default.cacheDir)
        .product(conf.getOrElse("debug")(default.debug))
        .map { case (cacheDir, debug) =>
          TypeclassWeakeningConfig(cacheDir, debug)
        }
    }
}
