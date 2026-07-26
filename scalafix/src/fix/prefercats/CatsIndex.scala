package fix.prefercats

import java.io.{ByteArrayOutputStream, InputStream}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

/** Record schema for the Cats-side normalized-body index. Field-for-field per
  * docs/PREFER_CATS_FUNCTIONS.md's design conclusions (#96 §2-3):
  * `render.isEmpty` *is* the public/internal flag -- there is no separate
  * boolean that could disagree with it.
  */
enum OwnerKind:
  case Typeclass, Syntax, DataType, Object

enum RenderKind:
  case Postfix, Operator, Prefix

final case class TypeParamSig(
    name: String,
    kindArity: Int,
    bounds: List[String]
)

final case class ParamSig(
    name: String,
    tpe: String,
    byName: Boolean,
    isImplicit: Boolean,
    hasDefault: Boolean
)

final case class RenderTemplate(kind: RenderKind, template: String)

final case class CatsFn(
    symbol: String,
    owner: String,
    ownerKind: OwnerKind,
    typeParams: List[TypeParamSig],
    valueParams: List[ParamSig],
    returnType: String,
    constraints: List[String],
    requiredImports: List[String],
    render: Option[RenderTemplate],
    body: IR,
    hash: Long
)

/** NDJSON codec plus classpath loader for the checked-in Cats index.
  * Hand-rolled rather than pulling in ujson/circe: the rule module must stay
  * light enough to sit in a consumer's `scalafixDependencies` (design #96 §4).
  */
object CatsIndex:

  /** Single manual sync point with `Versions.catsCore` in build.mill (Scala
    * source can't read a Mill build script at runtime).
    * `CatsIndexVersionSyncSuite` in the test module asserts these never drift
    * apart.
    */
  val expectedCatsCoreVersion: String = "2.13.0"

  val resourcePath: String =
    s"/purrism/cats-index-$expectedCatsCoreVersion.ndjson.gz"

  private val headerPrefix = "#cats-index-version:"

  // ---- minimal JSON writer -------------------------------------------------

  private def jstr(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case c    => sb.append(c)
    }
    sb.append('"').toString

  private def jarr(items: Seq[String]): String = items.mkString("[", ",", "]")

  private def encodeTypeParam(t: TypeParamSig): String =
    s"""{"name":${jstr(t.name)},"kindArity":${t.kindArity},"bounds":${jarr(
        t.bounds.map(jstr)
      )}}"""

  private def encodeParam(p: ParamSig): String =
    s"""{"name":${jstr(p.name)},"tpe":${jstr(
        p.tpe
      )},"byName":${p.byName},"isImplicit":${p.isImplicit},"hasDefault":${p.hasDefault}}"""

  private def encodeRender(r: RenderTemplate): String =
    s"""{"kind":${jstr(r.kind.toString)},"template":${jstr(r.template)}}"""

  def encode(fn: CatsFn): String =
    val renderJson = fn.render.map(encodeRender).getOrElse("null")
    s"""{"symbol":${jstr(fn.symbol)},"owner":${jstr(
        fn.owner
      )},"ownerKind":${jstr(
        fn.ownerKind.toString
      )},"typeParams":${fn.typeParams
        .map(encodeTypeParam)
        .mkString("[", ",", "]")},"valueParams":${fn.valueParams
        .map(encodeParam)
        .mkString("[", ",", "]")},"returnType":${jstr(
        fn.returnType
      )},"constraints":${jarr(
        fn.constraints.map(jstr)
      )},"requiredImports":${jarr(
        fn.requiredImports.map(jstr)
      )},"render":$renderJson,"body":${jstr(
        IR.canonical(fn.body)
      )},"hash":${fn.hash}}"""

  // ---- minimal JSON reader --------------------------------------------------

  private enum JVal:
    case JObj(fields: Map[String, JVal])
    case JArr(items: List[JVal])
    case JStr(value: String)
    case JNum(value: String)
    case JBool(value: Boolean)
    case JNull

  private class JsonParser(s: String):
    private var pos = 0
    private def peek: Char = s(pos)
    private def skipWs(): Unit = while pos < s.length && s(pos).isWhitespace do
      pos += 1

    def parse(): JVal =
      skipWs()
      val v = parseValue()
      v

    private def parseValue(): JVal =
      skipWs()
      peek match
        case '{' => parseObj()
        case '[' => parseArr()
        case '"' => JVal.JStr(parseString())
        case 't' => pos += 4; JVal.JBool(true)
        case 'f' => pos += 5; JVal.JBool(false)
        case 'n' => pos += 4; JVal.JNull
        case _   => JVal.JNum(parseNumber())

    private def parseString(): String =
      pos += 1 // opening quote
      val sb = new StringBuilder
      while peek != '"' do
        if peek == '\\' then
          pos += 1
          peek match
            case 'n'  => sb.append('\n')
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case c    => sb.append(c)
          pos += 1
        else
          sb.append(peek)
          pos += 1
      pos += 1 // closing quote
      sb.toString

    private def parseNumber(): String =
      val start = pos
      while pos < s.length && (peek.isDigit || peek == '-' || peek == '+' || peek == '.')
      do pos += 1
      s.substring(start, pos)

    private def parseObj(): JVal =
      pos += 1 // {
      skipWs()
      var fields = Map.empty[String, JVal]
      if peek == '}' then pos += 1
      else
        var continue = true
        while continue do
          skipWs()
          val key = parseString()
          skipWs()
          pos += 1 // :
          val value = parseValue()
          fields = fields + (key -> value)
          skipWs()
          if peek == ',' then pos += 1
          else
            pos += 1 // }
            continue = false
      JVal.JObj(fields)

    private def parseArr(): JVal =
      pos += 1 // [
      skipWs()
      var items = List.newBuilder[JVal]
      if peek == ']' then pos += 1
      else
        var continue = true
        while continue do
          items += parseValue()
          skipWs()
          if peek == ',' then
            pos += 1
            skipWs()
          else
            pos += 1 // ]
            continue = false
      JVal.JArr(items.result())

  private def str(v: JVal): String = v match
    case JVal.JStr(s) => s
    case other =>
      throw new IllegalArgumentException(s"expected string, got $other")
  private def bool(v: JVal): Boolean = v match
    case JVal.JBool(b) => b
    case other =>
      throw new IllegalArgumentException(s"expected bool, got $other")
  private def num(v: JVal): Long = v match
    case JVal.JNum(n) => n.toLong
    case other =>
      throw new IllegalArgumentException(s"expected number, got $other")
  private def arr(v: JVal): List[JVal] = v match
    case JVal.JArr(items) => items
    case other =>
      throw new IllegalArgumentException(s"expected array, got $other")
  private def obj(v: JVal): Map[String, JVal] = v match
    case JVal.JObj(fields) => fields
    case other =>
      throw new IllegalArgumentException(s"expected object, got $other")

  def decode(line: String): CatsFn =
    val fields = obj(JsonParser(line).parse())
    val typeParams = arr(fields("typeParams")).map { tp =>
      val f = obj(tp)
      TypeParamSig(
        str(f("name")),
        num(f("kindArity")).toInt,
        arr(f("bounds")).map(str)
      )
    }
    val valueParams = arr(fields("valueParams")).map { vp =>
      val f = obj(vp)
      ParamSig(
        str(f("name")),
        str(f("tpe")),
        bool(f("byName")),
        bool(f("isImplicit")),
        bool(f("hasDefault"))
      )
    }
    val render = fields("render") match
      case JVal.JNull => None
      case other =>
        val f = obj(other)
        Some(
          RenderTemplate(RenderKind.valueOf(str(f("kind"))), str(f("template")))
        )
    CatsFn(
      symbol = str(fields("symbol")),
      owner = str(fields("owner")),
      ownerKind = OwnerKind.valueOf(str(fields("ownerKind"))),
      typeParams = typeParams,
      valueParams = valueParams,
      returnType = str(fields("returnType")),
      constraints = arr(fields("constraints")).map(str),
      requiredImports = arr(fields("requiredImports")).map(str),
      render = render,
      body = IR.parse(str(fields("body"))),
      hash = num(fields("hash"))
    )

  /** Deterministic byte layout: header line, then one record per line sorted by
    * `symbol` -- regenerating the index twice must be byte-identical.
    */
  def render(catsVersion: String, fns: Seq[CatsFn]): String =
    val header = s"$headerPrefix$catsVersion"
    val body = fns.sortBy(_.symbol).map(encode)
    (header +: body).mkString("\n") + "\n"

  def gzip(text: String): Array[Byte] =
    val baos = new ByteArrayOutputStream()
    val gz = new GZIPOutputStream(baos)
    try gz.write(text.getBytes("UTF-8"))
    finally gz.close()
    baos.toByteArray

  private def gunzip(in: InputStream): String =
    val gz = new GZIPInputStream(in)
    val baos = new ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var n = gz.read(buf)
    while n >= 0 do
      baos.write(buf, 0, n)
      n = gz.read(buf)
    baos.toString("UTF-8")

  /** Parses a full gzipped-NDJSON index document, asserting the header's pinned
    * cats-core version matches [[expectedCatsCoreVersion]]. Fails loudly
    * (throws) on any mismatch, per #96/#99 acceptance criteria -- never
    * silently falls back to a stale or wrong-version index.
    */
  def parseDocument(text: String): Seq[CatsFn] =
    val lines = text.linesIterator.filter(_.nonEmpty).toList
    lines match
      case header :: records if header.startsWith(headerPrefix) =>
        val version = header.drop(headerPrefix.length)
        if version != expectedCatsCoreVersion then
          throw new IllegalStateException(
            s"Cats index version mismatch: index was generated for cats-core $version, " +
              s"but this build expects ${expectedCatsCoreVersion}. Regenerate the index " +
              "(mill catsIndex.generate) after bumping Versions.catsCore."
          )
        records.map(decode)
      case _ =>
        throw new IllegalStateException(
          "Cats index is missing its version header line"
        )

  /** Loads the checked-in index from the classpath
    * (`scalafix/resources${resourcePath}`).
    */
  def load(): Seq[CatsFn] =
    val in = Option(getClass.getResourceAsStream(resourcePath)).getOrElse(
      throw new IllegalStateException(
        s"Cats index resource not found on classpath: $resourcePath"
      )
    )
    try parseDocument(gunzip(in))
    finally in.close()
