package docs

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Renders `docs/findings.tsv` (header `# purrism <version>`, one tab-separated
  * row per `Finding`: code, rule, title, explanation, instruction, doc) as a
  * markdown table for `docs/findings.md`.
  */
object Findings:
  def renderTable(tsvPath: Path): String =
    val lines =
      Files.readAllLines(tsvPath, StandardCharsets.UTF_8).asScala.toList
    val rows = lines.filterNot(_.startsWith("# ")).filter(_.nonEmpty)
    val header = "| code | title | explanation | instruction | doc |"
    val divider = "|---|---|---|---|---|"
    val body = rows.map { line =>
      line.split("\t", -1) match
        case Array(code, _rule, title, explanation, instruction, doc) =>
          // A literal `|` in a field would end the table cell.
          val t = cell(title)
          val e = cell(explanation)
          val i = cell(instruction)
          s"| `$code` | $t | $e | $i | [$doc](index.md$doc) |"
        case other =>
          throw new IllegalArgumentException(
            s"bad findings.tsv row (${other.length} fields): $line"
          )
    }
    (header :: divider :: body).mkString("\n")

  private def cell(field: String): String = field.replace("|", "\\|")
