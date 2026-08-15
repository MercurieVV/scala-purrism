package docs

object DocDiff:
  def render(before: String, after: String): String =
    val beforeLines = sourceLines(before)
    val afterLines = sourceLines(after)
    val lines = renderChanged(beforeLines, afterLines)
    s"""<pre class="purrism-word-diff"><code>${lines.mkString(
        "\n"
      )}</code></pre>"""

  def render(diff: String): String =
    val lines =
      trimBlankEdges(diff.linesIterator.map(normalizeMarker).toList)
    s"""<pre class="purrism-word-diff"><code>${renderLines(
        lines
      )}</code></pre>"""

  private def sourceLines(source: String): List[String] =
    dedent(trimBlankEdges(source.linesIterator.toList))

  private def dedent(lines: List[String]): List[String] =
    val indent =
      lines
        .filter(_.trim.nonEmpty)
        .map(_.takeWhile(_ == ' ').length)
        .minOption
        .getOrElse(0)
    if indent == 0 then lines else lines.map(_.drop(indent))

  private def normalizeMarker(line: String): String =
    val trimmedLeft = line.dropWhile(_.isWhitespace)
    if trimmedLeft.startsWith("-") || trimmedLeft.startsWith("+") then
      trimmedLeft
    else line

  private def trimBlankEdges(lines: List[String]): List[String] =
    lines.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse

  private def renderLines(lines: List[String]): String =
    def loop(rest: List[String]): List[String] =
      rest match
        case Nil => Nil
        case head :: tail if head.startsWith("-") =>
          val (removed, afterRemoved) = rest.span(_.startsWith("-"))
          val (added, afterAdded) = afterRemoved.span(_.startsWith("+"))
          renderChanged(
            removed.map(markerPayload),
            added.map(markerPayload)
          ) ++ loop(afterAdded)
        case head :: tail if head.startsWith("+") =>
          val (added, afterAdded) = rest.span(_.startsWith("+"))
          renderChanged(Nil, added.map(markerPayload)) ++ loop(afterAdded)
        case head :: tail =>
          line(escape(head)) :: loop(tail)

    loop(lines).mkString("\n")

  private def markerPayload(line: String): String =
    line.drop(1).stripPrefix(" ")

  private def renderChanged(
      removed: List[String],
      added: List[String]
  ): List[String] =
    val count = removed.length.max(added.length)
    (0 until count).toList.map { index =>
      val oldLine = removed.lift(index)
      val newLine = added.lift(index)
      (oldLine, newLine) match
        case (Some(oldValue), Some(newValue)) =>
          line(wordDiff(oldValue, newValue))
        case (Some(oldValue), None) => line(del(escape(oldValue)))
        case (None, Some(newValue)) => line(ins(escape(newValue)))
        case (None, None)           => ""
    }

  private def wordDiff(oldLine: String, newLine: String): String =
    if oldLine == newLine then escape(oldLine)
    else
      val prefixLen =
        oldLine.zip(newLine).takeWhile { case (a, b) => a == b }.length
      val oldRest = oldLine.drop(prefixLen)
      val newRest = newLine.drop(prefixLen)
      val suffixLen =
        oldRest.reverse
          .zip(newRest.reverse)
          .takeWhile { case (a, b) => a == b }
          .length
      val prefix = escape(oldLine.take(prefixLen))
      val suffix =
        if suffixLen == 0 then "" else escape(oldRest.takeRight(suffixLen))
      val oldMid = oldRest.dropRight(suffixLen)
      val newMid = newRest.dropRight(suffixLen)
      val oldPart = if oldMid.isEmpty then "" else del(escape(oldMid))
      val newPart = if newMid.isEmpty then "" else ins(escape(newMid))
      s"$prefix$oldPart$newPart$suffix"

  private def line(value: String): String =
    s"""<span class="purrism-word-diff-line">$value</span>"""

  private def del(value: String): String =
    s"""<span style="background:rgba(207,34,46,.18);color:#82071e;text-decoration:line-through;border-radius:3px;padding:0 2px">$value</span>"""

  private def ins(value: String): String =
    s"""<span style="background:rgba(46,160,67,.22);color:#116329;border-radius:3px;padding:0 2px">$value</span>"""

  private def escape(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
