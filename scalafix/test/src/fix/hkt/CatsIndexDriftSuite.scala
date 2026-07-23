package fix.hkt

import java.nio.charset.StandardCharsets

import munit.FunSuite

/** Checks the complete checked-in TSV format from classpath resources.
  *
  * Generator byte identity is deliberately carried by
  * `rtk mill scalafix.catsIndexCheck` in `prePush`: making this test invoke
  * `indexgen` would introduce a circular dependency from `scalafix.test` back
  * to the tooling module whose `moduleDeps` already contains `scalafix`.
  */
final class CatsIndexDriftSuite extends FunSuite {
  private final case class Table(
      header: List[String],
      rows: List[List[String]]
  )

  private val headers = Map(
    "typeclasses.tsv" ->
      List(
        "symbol",
        "parents",
        "kind",
        "typeParams",
        "depth",
        "renderName",
        "importPath",
        "public"
      ),
    "capabilities.tsv" ->
      List("typeclass", "method", "owner", "kind", "derived", "arity"),
    "syntax.tsv" ->
      List("syntaxMethod", "owner", "method", "importPath"),
    "stdlib.tsv" ->
      List("concreteMethod", "owner", "method", "note"),
    "gaps.tsv" ->
      List("typeclass", "reason", "tracked")
  )

  test("all Cats index files have canonical headers, rows, and bytes") {
    headers.foreach { case (fileName, expectedHeader) =>
      val bytes = resourceBytes(fileName)
      assert(
        !bytes.startsWith(Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte)),
        s"$fileName must not have a UTF-8 BOM"
      )
      val text = new String(bytes, StandardCharsets.UTF_8)
      assertEquals(
        text.getBytes(StandardCharsets.UTF_8).toSeq,
        bytes.toSeq,
        s"$fileName is not canonical UTF-8"
      )
      assert(text.endsWith("\n"), s"$fileName must end in one newline")
      assert(!text.endsWith("\n\n"), s"$fileName has a blank trailing line")
      assert(!text.contains("\r"), s"$fileName contains a carriage return")

      val table = parse(fileName, text)
      assertEquals(table.header, expectedHeader, s"wrong header in $fileName")
      table.rows.zipWithIndex.foreach { case (row, index) =>
        assertEquals(
          row.size,
          expectedHeader.size,
          s"wrong column count in $fileName data row ${index + 1}"
        )
        row.foreach(cell =>
          assertEquals(
            cell,
            cell.stripTrailing,
            s"trailing whitespace in $fileName data row ${index + 1}"
          )
        )
      }
      assertEquals(
        table.rows,
        table.rows.sortWith((left, right) => compareRows(left, right) < 0),
        s"$fileName is not sorted by its complete raw column tuple"
      )
      assertEquals(
        table.rows.distinct.size,
        table.rows.size,
        s"$fileName contains duplicate rows"
      )
    }
  }

  test(
    "parents are internally sorted and canonical hierarchy edges are present"
  ) {
    val typeclasses = load("typeclasses.tsv")
    val bySymbol = typeclasses.rows.iterator.map(row => row.head -> row).toMap
    typeclasses.rows.foreach { row =>
      val parents = commaSeparated(row(1))
      assertEquals(
        parents,
        parents.sorted,
        s"unsorted parents for ${row.head}"
      )
    }

    assertEquals(bySymbol("cats/Bifunctor#")(2), "Binary")
    assertEquals(bySymbol("cats/Bifoldable#")(2), "Binary")
    assertEquals(bySymbol("cats/Bitraverse#")(2), "Binary")
    assert(isAncestor("cats/Functor#", "cats/Apply#", bySymbol))
    assert(isAncestor("cats/Apply#", "cats/Applicative#", bySymbol))
    assert(isAncestor("cats/Apply#", "cats/FlatMap#", bySymbol))
    assert(isAncestor("cats/Applicative#", "cats/Monad#", bySymbol))
    assert(isAncestor("cats/FlatMap#", "cats/Monad#", bySymbol))
    assertEquals(
      commaSeparated(bySymbol("cats/MonadError#")(1)).toSet,
      Set("cats/ApplicativeError#", "cats/Monad#")
    )
  }

  test("canonical owners and every syntax target resolve to capabilities") {
    val capabilities = load("capabilities.tsv").rows
    val byTypeclassAndMethod =
      capabilities.iterator.map(row => (row(0), row(1)) -> row).toMap

    assertEquals(
      byTypeclassAndMethod(
        "cats/Applicative#" -> "cats/Applicative#map()."
      )(2),
      "cats/Functor#map()."
    )
    assertEquals(
      byTypeclassAndMethod(
        "cats/Parallel#" -> "cats/Parallel#flatMap()."
      )(2),
      "cats/Parallel#flatMap()."
    )
    assertNotEquals(
      byTypeclassAndMethod(
        "cats/Parallel#" -> "cats/Parallel#flatMap()."
      )(2),
      "cats/FlatMap#flatMap()."
    )

    val targets =
      capabilities.iterator.map(row => row(2) -> row(1)).toSet
    val syntax = load("syntax.tsv").rows
    assert(syntax.nonEmpty, "syntax.tsv must not be empty")
    assert(
      syntax.exists(row =>
        row == List(
          "cats/Functor.Ops#map().",
          "cats/Functor#map().",
          "cats/Functor#map().",
          "cats.syntax.functor.*"
        )
      ),
      "syntax.tsv must contain the SemanticDB symbol used by Functor map syntax"
    )
    syntax.foreach { row =>
      assert(
        targets(row(1) -> row(2)),
        s"unresolved syntax target for ${row.head}: ${row(1)} / ${row(2)}"
      )
    }
  }

  private def load(fileName: String): Table =
    parse(
      fileName,
      new String(resourceBytes(fileName), StandardCharsets.UTF_8)
    )

  private def resourceBytes(fileName: String): Array[Byte] = {
    val path = s"cats-index/$fileName"
    val stream = Option(getClass.getClassLoader.getResourceAsStream(path))
      .getOrElse(fail(s"missing classpath resource: $path"))
    try stream.readAllBytes()
    finally stream.close()
  }

  private def parse(fileName: String, text: String): Table = {
    val lines = text.split("\n", -1).toList
    assert(lines.nonEmpty, s"$fileName is empty")
    assertEquals(lines.last, "", s"$fileName lacks its trailing newline")
    val content = lines.init
    assert(content.nonEmpty, s"$fileName lacks a header")
    assert(content.forall(_.nonEmpty), s"$fileName contains a blank line")
    assert(content.head.startsWith("#"), s"$fileName header lacks #")
    Table(
      content.head.drop(1).split("\t", -1).toList,
      content.tail.map(_.split("\t", -1).toList)
    )
  }

  private def compareRows(left: List[String], right: List[String]): Int =
    left.iterator
      .zip(right.iterator)
      .map { case (a, b) => a.compareTo(b) }
      .find(_ != 0)
      .getOrElse(Integer.compare(left.size, right.size))

  private def commaSeparated(cell: String): List[String] =
    if (cell.isEmpty) Nil else cell.split(",", -1).toList

  private def isAncestor(
      ancestor: String,
      descendant: String,
      rows: Map[String, List[String]]
  ): Boolean = {
    def loop(current: String, seen: Set[String]): Boolean =
      commaSeparated(rows(current)(1)).exists { parent =>
        parent == ancestor ||
        (!seen(parent) && loop(parent, seen + parent))
      }
    loop(descendant, Set(descendant))
  }
}
