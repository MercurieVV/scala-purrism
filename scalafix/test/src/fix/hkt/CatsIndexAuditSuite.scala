package fix.hkt

import java.nio.charset.StandardCharsets

import munit.FunSuite

import scalafix.v1.Symbol

/** Enforces the gap-audit contract from `docs/design/PreferHKTTypeclasses.md`,
  * item 8: every public indexed Cats typeclass is either supported by
  * `CapabilitySolver`, or listed in `scalafix/resources/cats-index/gaps.tsv`
  * with a rationale. Enumerates `index.publicTypeclasses` — no typeclass name
  * is hard-coded here.
  */
final class CatsIndexAuditSuite extends FunSuite {
  private val gapsPath = "scalafix/resources/cats-index/gaps.tsv"
  private val typeclassesPath = "scalafix/resources/cats-index/typeclasses.tsv"

  private lazy val index: CatsIndex = CatsIndex.load()
  private lazy val gaps: List[GapRow] = loadGaps()

  private final case class GapRow(
      typeclass: String,
      reason: String,
      tracked: String
  )

  test(
    "no unlisted gap: every unsupported public typeclass is listed in gaps.tsv"
  ) {
    val listed = gaps.map(_.typeclass).toSet
    val unlisted = index.publicTypeclasses
      .filterNot(tc => supports(tc))
      .filterNot(tc => listed(tc.symbol.value))

    if (unlisted.nonEmpty) {
      val entries = unlisted
        .map { tc =>
          s"  ${tc.symbol.value}  (kind=${KindShape.render(tc.kind)}, depth=${tc.depth})"
        }
        .mkString("\n")
      val exampleSymbol = unlisted.head.symbol.value
      fail(
        s"""Unsupported Cats typeclass is not listed in $gapsPath:
           |$entries
           |Either teach CapabilitySolver to support it, or add a row:
           |  $exampleSymbol\t<why it is unsupported>\t#33""".stripMargin
      )
    }
  }

  test("no stale gap: every listed typeclass is still unsupported") {
    val bySymbol = index.typeclasses
    val stale = gaps.filter { gap =>
      bySymbol.get(Symbol(gap.typeclass)).exists(tc => supports(tc))
    }

    if (stale.nonEmpty) {
      val entries = stale
        .map(gap =>
          s"  ${gap.typeclass}  is now supported by CapabilitySolver; remove this row."
        )
        .mkString("\n")
      fail(
        s"""Stale gap in $gapsPath:
           |$entries""".stripMargin
      )
    }
  }

  test("no orphan gap: every listed typeclass is present in typeclasses.tsv") {
    val orphans =
      gaps.filterNot(gap => index.typeclasses.contains(Symbol(gap.typeclass)))

    if (orphans.nonEmpty) {
      val entries = orphans
        .map(gap => s"  ${gap.typeclass}  is not present in $typeclassesPath.")
        .mkString("\n")
      fail(
        s"""Orphan gap in $gapsPath:
           |$entries""".stripMargin
      )
    }
  }

  test("gaps.tsv rows are well-formed and sorted") {
    gaps.foreach { gap =>
      assert(gap.typeclass.nonEmpty, s"empty typeclass column in $gapsPath")
      assert(
        gap.reason.nonEmpty,
        s"empty reason column in $gapsPath for ${gap.typeclass}"
      )
      assert(
        gap.tracked.nonEmpty,
        s"empty tracked column in $gapsPath for ${gap.typeclass}"
      )
    }
    val symbols = gaps.map(_.typeclass)
    assertEquals(
      symbols,
      symbols.sorted,
      s"$gapsPath rows are not sorted by typeclass"
    )
  }

  /** `CapabilitySolver.solve` returns `Right` for `tc`'s own non-derived
    * primitives; a non-v1 kind (anything other than `Unary`) is unsupported
    * regardless of what the solver reports.
    */
  private def supports(tc: CatsTypeclass): Boolean =
    tc.kind == KindShape.Unary && CapabilitySolver.supports(tc.symbol, index)

  private def loadGaps(): List[GapRow] = {
    val stream = Option(
      getClass.getClassLoader.getResourceAsStream(CatsIndex.gapsResource)
    )
      .getOrElse(fail(s"missing classpath resource: ${CatsIndex.gapsResource}"))
    val text =
      try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    text
      .split("\n", -1)
      .toList
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .map { line =>
        line.split("\t", -1).toList match {
          case List(typeclass, reason, tracked) =>
            GapRow(typeclass, reason, tracked)
          case other =>
            fail(s"$gapsPath: expected 3 columns, got ${other.size}: $line")
        }
      }
  }
}
