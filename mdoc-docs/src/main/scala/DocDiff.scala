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
          line(highlight(head)) :: loop(tail)

    loop(lines).mkString("\n")

  private def markerPayload(line: String): String =
    line.drop(1).stripPrefix(" ")

  private def renderChanged(
      removed: List[String],
      added: List[String]
  ): List[String] =
    renderAligned(removed, added)

  private def renderAligned(
      removed: List[String],
      added: List[String]
  ): List[String] =
    (removed, added) match
      case (Nil, Nil) => Nil
      case (oldValue :: oldTail, newValue :: newTail)
          if isSimilar(oldValue, newValue) =>
        line(wordDiff(oldValue, newValue)) :: renderAligned(oldTail, newTail)
      case (oldValue :: oldTail, newValue :: newTail) =>
        val nextAddedMatch = added.indexWhere(isSimilar(oldValue, _))
        val nextRemovedMatch = removed.indexWhere(isSimilar(_, newValue))
        if nextAddedMatch > 0 &&
          (nextRemovedMatch < 0 || nextAddedMatch <= nextRemovedMatch)
        then
          added
            .take(nextAddedMatch)
            .map(value => line(ins(value))) ++
            renderAligned(removed, added.drop(nextAddedMatch))
        else if nextRemovedMatch > 0 then
          removed
            .take(nextRemovedMatch)
            .map(value => line(del(value))) ++
            renderAligned(removed.drop(nextRemovedMatch), added)
        else
          line(del(oldValue)) :: line(ins(newValue)) ::
            renderAligned(oldTail, newTail)
      case (oldValue :: oldTail, Nil) =>
        line(del(oldValue)) :: renderAligned(oldTail, Nil)
      case (Nil, newValue :: newTail) =>
        line(ins(newValue)) :: renderAligned(Nil, newTail)

  private def isSimilar(oldLine: String, newLine: String): Boolean =
    val prefixLen =
      oldLine.zip(newLine).takeWhile { case (a, b) => a == b }.length
    val oldRest = oldLine.drop(prefixLen)
    val newRest = newLine.drop(prefixLen)
    val suffixLen = commonSuffixLen(oldRest, newRest)
    val shared = prefixLen + suffixLen
    val shorter = oldLine.length.min(newLine.length)
    oldLine == newLine || sameNamedDefinition(oldLine, newLine) ||
    sameNamedClass(oldLine, newLine) ||
    (shared >= 4 && shared * 2 >= shorter)

  private def sameNamedDefinition(oldLine: String, newLine: String): Boolean =
    val definition =
      raw"""\s*(?:private\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\b""".r
    (
      definition.findFirstMatchIn(oldLine),
      definition.findFirstMatchIn(newLine)
    ) match
      case (Some(oldMatch), Some(newMatch)) =>
        oldMatch.group(1) == newMatch.group(1)
      case _ => false

  private def wordDiff(oldLine: String, newLine: String): String =
    if oldLine == newLine then highlight(oldLine)
    else
      defSignatureDiff(oldLine, newLine).getOrElse(
        classConstructorDiff(oldLine, newLine).getOrElse(
          genericWordDiff(oldLine, newLine, keepTypeArgumentsTogether = true)
        )
      )

  private def genericWordDiff(
      oldLine: String,
      newLine: String,
      keepTypeArgumentsTogether: Boolean
  ): String =
    val prefixLen =
      oldLine.zip(newLine).takeWhile { case (a, b) => a == b }.length
    val oldRest = oldLine.drop(prefixLen)
    val newRest = newLine.drop(prefixLen)
    val suffixLen =
      if keepTypeArgumentsTogether then commonSuffixLen(oldRest, newRest)
      else rawCommonSuffixLen(oldRest, newRest)
    val prefix = highlight(oldLine.take(prefixLen))
    val suffix =
      if suffixLen == 0 then "" else highlight(oldRest.takeRight(suffixLen))
    val oldMid = oldRest.dropRight(suffixLen)
    val newMid = newRest.dropRight(suffixLen)
    val oldPart = if oldMid.isEmpty then "" else del(oldMid)
    val newPart = if newMid.isEmpty then "" else ins(newMid)
    s"$prefix$oldPart$newPart$suffix"

  private final case class DefSignature(
      prefix: String,
      typeParams: String,
      params: String,
      returnType: String,
      suffix: String
  )

  private final case class ClassConstructor(
      prefix: String,
      typeParams: String,
      params: String,
      suffix: String
  )

  private def defSignatureDiff(
      oldLine: String,
      newLine: String
  ): Option[String] =
    for
      oldSig <- parseDefSignature(oldLine)
      newSig <- parseDefSignature(newLine)
      if oldSig.prefix == newSig.prefix
    yield diffPart(oldSig.prefix, newSig.prefix) +
      diffPart(oldSig.typeParams, newSig.typeParams) +
      diffPart(oldSig.params, newSig.params) +
      diffPart(oldSig.returnType, newSig.returnType) +
      diffPart(oldSig.suffix, newSig.suffix)

  private def classConstructorDiff(
      oldLine: String,
      newLine: String
  ): Option[String] =
    for
      oldClass <- parseClassConstructor(oldLine)
      newClass <- parseClassConstructor(newLine)
      if oldClass.prefix == newClass.prefix
    yield diffPart(oldClass.prefix, newClass.prefix) +
      diffPart(oldClass.typeParams, newClass.typeParams) +
      diffPart(oldClass.params, newClass.params) +
      diffPart(oldClass.suffix, newClass.suffix)

  private def diffPart(oldPart: String, newPart: String): String =
    if oldPart == newPart then highlight(oldPart)
    else genericWordDiff(oldPart, newPart, keepTypeArgumentsTogether = false)

  private def parseDefSignature(line: String): Option[DefSignature] =
    val defIndex = line.indexOf("def ")
    if defIndex < 0 then None
    else
      val nameStart = defIndex + "def ".length
      val nameEnd = readIdentifierEnd(line, nameStart)
      if nameEnd == nameStart then None
      else
        val typeStart = skipWhitespace(line, nameEnd)
        val (typeParams, afterTypeParams) =
          if typeStart < line.length && line.charAt(typeStart) == '[' then
            balancedPart(line, typeStart, '[', ']')
              .map((part, end) => (part, end))
              .getOrElse(("", typeStart))
          else ("", typeStart)
        val paramsStart = skipWhitespace(line, afterTypeParams)
        if paramsStart >= line.length || line.charAt(paramsStart) != '(' then
          None
        else
          balancedPart(line, paramsStart, '(', ')').flatMap {
            (params, afterParams) =>
              val afterParamsSpace = skipWhitespace(line, afterParams)
              val (returnType, suffix) =
                if afterParamsSpace < line.length && line.charAt(
                    afterParamsSpace
                  ) == ':'
                then
                  val equalsIndex = line.indexOf("=", afterParamsSpace)
                  if equalsIndex < 0 then (line.substring(afterParamsSpace), "")
                  else
                    val beforeEquals =
                      line.substring(afterParamsSpace, equalsIndex)
                    val returnType = beforeEquals.stripTrailing()
                    val afterReturn = line.substring(
                      afterParamsSpace + returnType.length
                    )
                    (returnType, afterReturn)
                else ("", line.substring(afterParamsSpace))
              Some(
                DefSignature(
                  prefix = line.substring(0, nameEnd),
                  typeParams = typeParams,
                  params = params,
                  returnType = returnType,
                  suffix = suffix
                )
              )
          }

  private def parseClassConstructor(line: String): Option[ClassConstructor] =
    val classIndex = line.indexOf("class ")
    if classIndex < 0 then None
    else
      val nameStart = classIndex + "class ".length
      val nameEnd = readIdentifierEnd(line, nameStart)
      if nameEnd == nameStart then None
      else
        val typeStart = skipWhitespace(line, nameEnd)
        val (typeParams, afterTypeParams) =
          if typeStart < line.length && line.charAt(typeStart) == '[' then
            balancedPart(line, typeStart, '[', ']')
              .map((part, end) => (part, end))
              .getOrElse(("", typeStart))
          else ("", typeStart)
        val paramsStart = skipWhitespace(line, afterTypeParams)
        if paramsStart >= line.length || line.charAt(paramsStart) != '(' then
          None
        else
          balancedPart(line, paramsStart, '(', ')').map {
            (params, afterParams) =>
              ClassConstructor(
                prefix = line.substring(0, nameEnd),
                typeParams = typeParams,
                params = params,
                suffix = line.substring(afterParams)
              )
          }

  private def sameNamedClass(oldLine: String, newLine: String): Boolean =
    (
      parseClassConstructor(oldLine),
      parseClassConstructor(newLine)
    ) match
      case (Some(oldClass), Some(newClass)) =>
        oldClass.prefix == newClass.prefix
      case _ => false

  private def readIdentifierEnd(value: String, start: Int): Int =
    value
      .drop(start)
      .takeWhile(ch => ch.isLetterOrDigit || ch == '_')
      .length + start

  private def skipWhitespace(value: String, start: Int): Int =
    start + value.drop(start).takeWhile(_.isWhitespace).length

  private def balancedPart(
      value: String,
      start: Int,
      open: Char,
      close: Char
  ): Option[(String, Int)] =
    if start >= value.length || value.charAt(start) != open then None
    else
      var depth = 0
      var index = start
      var end = -1
      while index < value.length && end < 0 do
        val ch = value.charAt(index)
        if ch == open then depth += 1
        else if ch == close then
          depth -= 1
          if depth == 0 then end = index + 1
        index += 1
      if end < 0 then None else Some((value.substring(start, end), end))

  private def commonSuffixLen(oldRest: String, newRest: String): Int =
    val rawLen = rawCommonSuffixLen(oldRest, newRest)
    avoidTypeArgumentSplit(oldRest.takeRight(rawLen), rawLen)

  private def rawCommonSuffixLen(oldRest: String, newRest: String): Int =
    oldRest.reverse
      .zip(newRest.reverse)
      .takeWhile { case (a, b) => a == b }
      .length

  private def avoidTypeArgumentSplit(suffix: String, suffixLen: Int): Int =
    if suffix.startsWith("[") then
      matchingBracketEnd(suffix) match
        case Some(end) => suffixLen - end - 1
        case None      => suffixLen
    else suffixLen

  private def matchingBracketEnd(value: String): Option[Int] =
    value.zipWithIndex
      .foldLeft(Option.empty[(Int, Int)]) {
        case (None, ('[', index)) => Some((1, index))
        case (None, _)            => None
        case (Some((depth, _)), ('[', _)) =>
          Some((depth + 1, -1))
        case (Some((1, _)), (']', index)) =>
          Some((0, index))
        case (Some((depth, _)), (']', _)) =>
          Some((depth - 1, -1))
        case (state, _) => state
      }
      .collect { case (0, index) => index }

  private def line(value: String): String =
    s"""<span class="purrism-word-diff-line">$value</span>"""

  private def del(value: String): String =
    s"""<span class="purrism-word-diff-del">${highlight(
        value
      )}</span>"""

  private def ins(value: String): String =
    s"""<span class="purrism-word-diff-ins">${highlight(
        value
      )}</span>"""

  private val scalaKeywords: Set[String] =
    Set(
      "case",
      "catch",
      "class",
      "def",
      "else",
      "extension",
      "false",
      "final",
      "for",
      "given",
      "if",
      "import",
      "match",
      "new",
      "object",
      "opaque",
      "private",
      "then",
      "throw",
      "trait",
      "true",
      "try",
      "type",
      "val",
      "var",
      "while",
      "with",
      "yield"
    )

  private def highlight(value: String): String =
    val pattern =
      raw""""(?:\\.|[^"\\])*"|//.*|[A-Za-z_][A-Za-z0-9_]*|\b\d+(?:\.\d+)?\b""".r
    val out = new StringBuilder
    var index = 0
    for m <- pattern.findAllMatchIn(value) do
      out.append(escape(value.substring(index, m.start)))
      out.append(highlightToken(m.matched))
      index = m.end
    out.append(escape(value.substring(index)))
    out.toString

  private def highlightToken(token: String): String =
    val escaped = escape(token)
    if token.startsWith("\"") then
      s"""<span class="purrism-token-string">$escaped</span>"""
    else if token.startsWith("//") then
      s"""<span class="purrism-token-comment">$escaped</span>"""
    else if scalaKeywords.contains(token) then
      s"""<span class="purrism-token-keyword">$escaped</span>"""
    else if token.headOption.exists(_.isUpper) then
      s"""<span class="purrism-token-type">$escaped</span>"""
    else if token.headOption.exists(_.isDigit) then
      s"""<span class="purrism-token-number">$escaped</span>"""
    else escaped

  private def escape(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
