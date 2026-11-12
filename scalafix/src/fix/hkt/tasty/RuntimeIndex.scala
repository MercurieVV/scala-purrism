package fix.hkt.tasty

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

import scala.annotation.tailrec

import fix.hkt.CatsIndex
import scalafix.v1.Symbol

/** Builds a [[CatsIndex]] from the TASTy of the jars a set of typeclass symbols
  * (and their ancestors) live in, at rule runtime.
  */
object RuntimeIndex {

  /** A jar's rows; `source` is `None` when its scan failed (no rows). */
  private final case class JarRows(
      rows: InspectedRows,
      source: Option[IndexSource]
  )

  private val EmptyRows = InspectedRows(Nil, Nil, Nil, Nil, Nil)

  private val jarRows = new ConcurrentHashMap[String, JarRows]()
  private val indexes = new ConcurrentHashMap[List[Path], CatsIndex]()

  /** The index over the jars of `seeds` closed over their ancestors' jars.
    *
    * Each jar's rows come from [[TastyScanCache]], scanned against the whole
    * `classpath`. A jar that fails to scan contributes nothing; `Left` only
    * when the merged rows do not form a valid index. Memoised per closure.
    */
  def load(
      classpath: List[Path],
      seeds: Set[Symbol],
      config: CacheConfig
  ): Either[String, CatsIndex] = {
    val seedJars = seeds.toList
      .sortBy(_.value)
      .flatMap(JarLocator.jarOf(_, classpath))
      .distinct
    val closure = close(classpath, seedJars, Map.empty, config)
    val jars = closure.keys.toList.sortBy(_.toString)
    Option(indexes.get(jars)) match {
      case Some(index) => Right(index)
      case None =>
        build(closure.values.toList).map(index =>
          Option(indexes.putIfAbsent(jars, index)).getOrElse(index)
        )
    }
  }

  @tailrec
  private def close(
      classpath: List[Path],
      pending: List[Path],
      done: Map[Path, JarRows],
      config: CacheConfig
  ): Map[Path, JarRows] =
    pending.filterNot(done.contains) match {
      case Nil => done
      case fresh =>
        val loaded =
          done ++ fresh.map(jar => jar -> rowsOf(jar, classpath, config))
        val typeclassRows = loaded.valuesIterator.flatMap(_.rows.typeclasses)
        val (known, parents) = typeclassRows.foldLeft(
          (Set.empty[String], Set.empty[String])
        ) { case ((symbols, parentSymbols), row) =>
          (
            symbols ++ row.headOption,
            parentSymbols ++ row.lift(1).toList.flatMap(splitList)
          )
        }
        val next = (parents -- known).toList.sorted
          .flatMap(parent => JarLocator.jarOf(Symbol(parent), classpath))
          .distinct
        close(classpath, next, loaded, config)
    }

  private def rowsOf(
      jar: Path,
      classpath: List[Path],
      config: CacheConfig
  ): JarRows = {
    val memoKey =
      s"${config.dir.fold("")(_.toString)}|${TastyScanCache.key(jar)}"
    Option(jarRows.get(memoKey)).getOrElse {
      val computed = TastyScanCache
        .rows(jar, classpath, config)
        .fold(
          _ => JarRows(EmptyRows, None),
          { case (rows, source) => JarRows(rows, Some(source)) }
        )
      Option(jarRows.putIfAbsent(memoKey, computed)).getOrElse(computed)
    }
  }

  private def build(jars: List[JarRows]): Either[String, CatsIndex] = {
    val merged = TypeclassInspector.finish(
      jars.map(_.rows).foldLeft(EmptyRows) { (acc, rows) =>
        InspectedRows(
          acc.typeclasses ++ rows.typeclasses,
          acc.capabilities ++ rows.capabilities,
          acc.syntax ++ rows.syntax,
          acc.evidence ++ rows.evidence,
          acc.aliases ++ rows.aliases
        )
      }
    )
    // The hand-written stdlib table names cats capabilities; it only applies
    // when the bundled cats rows are part of the index.
    val stdlib =
      if (jars.exists(_.source.contains(IndexSource.Bundled)))
        TastyScanCache.readResource(CatsIndex.stdlibResource)
      else Right(Nil)
    for {
      stdlibRows <- stdlib
      index <- CatsIndex.parse(
        lines(merged.typeclasses),
        lines(merged.capabilities),
        lines(merged.syntax),
        lines(stdlibRows)
      )
      withTables <- index.withTables(
        lines(merged.evidence),
        lines(merged.aliases)
      )
    } yield withTables
  }

  private def lines(rows: List[List[String]]): Iterator[String] =
    rows.iterator.map(_.mkString("\t"))

  private def splitList(cell: String): List[String] =
    if (cell.isEmpty) Nil else cell.split(",", -1).toList
}
