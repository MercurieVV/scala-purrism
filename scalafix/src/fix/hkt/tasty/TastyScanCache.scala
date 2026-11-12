package fix.hkt.tasty

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using

/** Where scanned per-jar rows are cached, and whether to log each lookup.
  *
  * @param dir
  *   cache root; `None` disables the disk cache (every lookup scans)
  * @param debug
  *   print one `TypeclassWeakening index:` line per lookup to stderr
  */
private[fix] final case class CacheConfig(dir: Option[Path], debug: Boolean)

private[fix] object CacheConfig {

  /** `~/.cache/scala-purrism/tasty-index`, debug off. */
  val default: CacheConfig =
    CacheConfig(
      Option(System.getProperty("user.home"))
        .map(home => Paths.get(home, ".cache", "scala-purrism", "tasty-index")),
      debug = false
    )
}

/** Where a jar's rows came from. */
private[fix] enum IndexSource {
  case Bundled, Disk, Scan
}

/** Per-jar [[TypeclassInspector.scanJars]] rows, cached on disk.
  *
  * Rows are the unfinished, self-contained per-jar form; callers merge the rows
  * of several jars and run [[TypeclassInspector.finish]] over the union.
  */
private[fix] object TastyScanCache {

  /** Bump when the cached row format or the inspector's output changes. */
  val FormatVersion: String = "1"

  private val Tables =
    List("typeclasses", "capabilities", "syntax", "evidence", "aliases")

  private val BundledResources = Tables.map(table => s"cats-index/$table.tsv")

  private def bundledVersion: String =
    fix.prefercats.CatsIndex.expectedCatsCoreVersion

  /** sha1 of absolute path, size, mtime, [[FormatVersion]] and Scala version.
    */
  def key(jar: Path): String = {
    val absolute = jar.toAbsolutePath.normalize
    val size = Try(Files.size(absolute)).getOrElse(-1L)
    val mtime =
      Try(Files.getLastModifiedTime(absolute).toMillis).getOrElse(-1L)
    val material =
      s"$absolute|$size|$mtime|$FormatVersion|${scala.util.Properties.versionNumberString}"
    MessageDigest
      .getInstance("SHA-1")
      .digest(material.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
  }

  /** Whether `jar` is the cats-core / cats-kernel build the bundled index was
    * generated from.
    */
  def isBundled(jar: Path): Boolean = {
    val name = Option(jar.getFileName).map(_.toString).getOrElse("")
    name == s"cats-core_3-$bundledVersion.jar" ||
    name == s"cats-kernel_3-$bundledVersion.jar"
  }

  /** The jar's rows: bundled resources for the bundled cats jars, else the disk
    * entry, else a fresh [[TypeclassInspector.AnyScope]] scan (stored on disk
    * when it succeeds). A failed scan is `Left` and is not cached.
    */
  def rows(
      jar: Path,
      deps: List[Path],
      config: CacheConfig
  ): Either[String, (InspectedRows, IndexSource)] = {
    val start = System.nanoTime()
    val result =
      if (isBundled(jar)) bundledRows.map(_ -> IndexSource.Bundled)
      else {
        val entry = config.dir.map(_.resolve(key(jar)))
        entry.flatMap(readEntry) match {
          case Some(cached) => Right(cached -> IndexSource.Disk)
          case None =>
            scan(jar, deps).map(scanned =>
              config.dir.zip(entry).fold(scanned -> IndexSource.Scan) {
                case (dir, target) => store(dir, target, scanned)
              }
            )
        }
      }
    if (config.debug) {
      val ms = (System.nanoTime() - start) / 1000000
      val source = result.fold(_ => "Failed", _._2.toString)
      System.err.println(s"TypeclassWeakening index: $jar $source ${ms}ms")
    }
    result
  }

  private lazy val bundledRows: Either[String, InspectedRows] =
    BundledResources
      .foldLeft[Either[String, List[List[List[String]]]]](Right(Nil)) {
        (acc, resource) =>
          for {
            tables <- acc
            table <- readResource(resource)
          } yield tables :+ table
      }
      .flatMap(fromTables)

  /** Scans a private copy of `jar`: the compiler behind `TastyInspector` caches
    * archive contents by path for the life of the JVM, so a jar rebuilt in
    * place would otherwise be read with its old contents. The original path is
    * dropped from `deps` so it cannot shadow the copy.
    */
  private def scan(jar: Path, deps: List[Path]): Either[String, InspectedRows] =
    Try(Files.createTempDirectory("purrism-tasty-scan")).toEither.left
      .map(error => s"cannot create scan directory: ${error.getMessage}")
      .flatMap { scratch =>
        val copy = scratch.resolve(
          Option(jar.getFileName).map(_.toString).getOrElse("scan.jar")
        )
        val result = Try(Files.copy(jar, copy)).toEither.left
          .map(error => s"cannot copy $jar: ${error.getMessage}")
          .flatMap(_ =>
            TypeclassInspector.scanJars(
              List(copy),
              deps.filterNot(_ == jar),
              TypeclassInspector.AnyScope
            )
          )
          .map(normalize)
        deleteTree(scratch)
        result
      }

  private def normalize(rows: InspectedRows): InspectedRows =
    InspectedRows(
      TypeclassInspector.sortRows(rows.typeclasses),
      TypeclassInspector.sortRows(rows.capabilities),
      TypeclassInspector.sortRows(rows.syntax),
      TypeclassInspector.sortRows(rows.evidence),
      TypeclassInspector.sortRows(rows.aliases)
    )

  private def tablesOf(rows: InspectedRows): List[List[List[String]]] =
    List(
      rows.typeclasses,
      rows.capabilities,
      rows.syntax,
      rows.evidence,
      rows.aliases
    )

  private def fromTables(
      tables: List[List[List[String]]]
  ): Either[String, InspectedRows] =
    tables match {
      case List(typeclasses, capabilities, syntax, evidence, aliases) =>
        Right(
          InspectedRows(typeclasses, capabilities, syntax, evidence, aliases)
        )
      case other => Left(s"expected ${Tables.size} tables, got ${other.size}")
    }

  /** Write into a unique temp dir, then atomically rename it into place. When
    * another writer got there first, its (complete) entry wins.
    */
  private def store(
      dir: Path,
      target: Path,
      rows: InspectedRows
  ): (InspectedRows, IndexSource) = {
    val temp = dir.resolve(s"${target.getFileName}.tmp-${UUID.randomUUID()}")
    val written = Try {
      Files.createDirectories(temp)
      Tables.zip(tablesOf(rows)).foreach { case (table, tableRows) =>
        Files.writeString(
          temp.resolve(s"$table.tsv"),
          tableRows.map(_.mkString("\t")).mkString("", "\n", "\n"),
          StandardCharsets.UTF_8
        )
      }
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
    }
    if (written.isFailure) deleteTree(temp)
    written.toOption
      .fold(readEntry(target).getOrElse(rows))(_ => rows) -> IndexSource.Scan
  }

  private def readEntry(entry: Path): Option[InspectedRows] =
    Option
      .when(Files.isDirectory(entry))(
        Tables.foldLeft[Option[List[List[List[String]]]]](Some(Nil)) {
          (acc, table) =>
            for {
              tables <- acc
              text <- Try(
                Files.readString(entry.resolve(s"$table.tsv"))
              ).toOption
            } yield tables :+ parseLines(text.split("\n", -1).toList)
        }
      )
      .flatten
      .flatMap(tables => fromTables(tables).toOption)

  private[tasty] def readResource(
      resource: String
  ): Either[String, List[List[String]]] =
    Option(getClass.getClassLoader.getResourceAsStream(resource))
      .toRight(s"missing classpath resource: $resource")
      .flatMap(stream =>
        Using(stream)(in =>
          new String(in.readAllBytes(), StandardCharsets.UTF_8)
        ).toEither.left
          .map(error => s"cannot read $resource: ${error.getMessage}")
      )
      .map(text => parseLines(text.split("\n", -1).toList))

  private def parseLines(lines: List[String]): List[List[String]] =
    lines
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .map(_.split("\t", -1).toList)

  private def deleteTree(path: Path): Unit = {
    val _ = Try {
      if (Files.exists(path))
        Using
          .resource(Files.walk(path))(
            _.sorted(Comparator.reverseOrder[Path]())
              .iterator()
              .asScala
              .toList
          )
          .foreach(Files.deleteIfExists(_))
    }
  }
}
