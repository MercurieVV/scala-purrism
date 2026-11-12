package fix.hkt.tasty

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*
import scala.util.Using

import scalafix.v1.Symbol

/** Finds the classpath jar whose TASTy defines a SemanticDB symbol. */
object JarLocator {

  private val entries = new ConcurrentHashMap[Path, Set[String]]()

  /** `a/b/C#` (or any member of it) ⇒ the first jar holding `a/b/C.tasty`.
    *
    * Only regular `.jar` files are considered; a jar's entry listing is read
    * once per process. Unreadable jars count as empty.
    */
  def jarOf(symbol: Symbol, classpath: List[Path]): Option[Path] =
    tastyEntry(symbol).flatMap { entry =>
      classpath.find(path => isJar(path) && entriesOf(path).contains(entry))
    }

  /** The `.tasty` entry name of the top-level definition owning `symbol`. */
  private[tasty] def tastyEntry(symbol: Symbol): Option[String] = {
    val value = symbol.value
    val packageEnd = value.lastIndexOf('/') + 1
    val topLevel = value
      .substring(packageEnd)
      .takeWhile(ch => ch != '#' && ch != '.')
      .stripPrefix("`")
      .stripSuffix("`")
    Option.when(topLevel.nonEmpty)(
      value.substring(0, packageEnd) + topLevel + ".tasty"
    )
  }

  private def isJar(path: Path): Boolean =
    path.getFileName.toString.endsWith(".jar") && Files.isRegularFile(path)

  private def entriesOf(jar: Path): Set[String] =
    entries.computeIfAbsent(
      jar,
      path =>
        Using(new ZipFile(path.toFile)) { zip =>
          zip
            .entries()
            .asScala
            .map(_.getName)
            .filter(_.endsWith(".tasty"))
            .toSet
        }.getOrElse(Set.empty[String])
    )
}
