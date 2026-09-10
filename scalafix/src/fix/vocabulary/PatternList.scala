package fix.vocabulary

import scala.util.Try
import scala.util.matching.Regex

final class PatternList private (compiled: List[Regex]) {
  def matches(fqcn: String): Boolean = compiled.exists(_.matches(fqcn))
}

object PatternList {
  val empty: PatternList = new PatternList(Nil)

  def compile(patterns: List[String]): Either[String, PatternList] = {
    val attempts = patterns.map(p => p -> Try(p.r))
    attempts.collectFirst { case (raw, scala.util.Failure(e)) =>
      s"invalid pattern '$raw': ${e.getMessage}"
    } match {
      case Some(err) => Left(err)
      case None      => Right(new PatternList(attempts.map(_._2.get)))
    }
  }

  /** `cats/arrow/Arrow#` -> `cats.arrow.Arrow`; SemanticDB terminates every
    * segment with `#` (type/class), `.` (term/object) or `().` (method) —
    * collapse all three to `.`, replace path separators with dots, and drop the
    * resulting trailing dot.
    */
  def normalize(rawSymbolValue: String): String =
    rawSymbolValue
      .replace("().", ".")
      .replace('#', '.')
      .replace('/', '.')
      .stripSuffix(".")
}
