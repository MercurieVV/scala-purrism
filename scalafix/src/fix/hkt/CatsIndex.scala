package fix.hkt

import java.nio.charset.StandardCharsets

import scalafix.v1.Symbol

/** In-memory, queryable Cats capability index, loaded from the checked-in TSV
  * artifacts under `scalafix/resources/cats-index/` (see
  * `docs/design/PreferHKTTypeclasses.md`, item 2 and item 7).
  */
final class CatsIndex(
    val typeclasses: Map[Symbol, CatsTypeclass],
    val capabilities: Map[Symbol, List[Capability]],
    val syntax: Map[Symbol, Capability]
) {

  /** Every capability whose method or owner is `method`, across all
    * typeclasses, in stable order (by typeclass symbol string).
    */
  def providersOf(method: Symbol): List[Capability] =
    capabilities.valuesIterator.flatten
      .filter(capability => capability.method == method || capability.owner == method)
      .toList
      .sortBy(_.typeclass.value)

  /** The override-chain root owner of `method`, if `method` is indexed. */
  def primitiveOwner(method: Symbol): Option[Symbol] =
    capabilities.valuesIterator.flatten
      .filter(_.method == method)
      .toList
      .sortBy(_.typeclass.value)
      .headOption
      .map(_.owner)

  def resolveSyntax(method: Symbol): Option[Capability] = syntax.get(method)

  /** Transitive, cycle-safe ancestry over `CatsTypeclass.parents`. */
  def isAncestor(ancestor: Symbol, descendant: Symbol): Boolean = {
    def loop(current: Symbol, visited: Set[Symbol]): Boolean =
      typeclasses.get(current) match {
        case None => false
        case Some(tc) =>
          tc.parents.exists { parent =>
            parent == ancestor ||
            (!visited(parent) && loop(parent, visited + parent))
          }
      }
    if (ancestor == descendant) false
    else loop(descendant, Set(descendant))
  }

  def depth(typeclass: Symbol): Int =
    typeclasses.get(typeclass).map(_.depth).getOrElse(0)

  def publicTypeclasses: List[CatsTypeclass] =
    typeclasses.valuesIterator
      .filter(_.isPublic)
      .toList
      .sortBy(_.symbol.value)
}

object CatsIndex {
  val capabilitiesResource: String = "cats-index/capabilities.tsv"
  val typeclassesResource: String = "cats-index/typeclasses.tsv"
  val syntaxResource: String = "cats-index/syntax.tsv"
  val stdlibResource: String = "cats-index/stdlib.tsv"
  val gapsResource: String = "cats-index/gaps.tsv"

  def load(): CatsIndex = {
    val typeclassLines = readResourceLines(typeclassesResource)
    val capabilityLines = readResourceLines(capabilitiesResource)
    val syntaxLines = readResourceLines(syntaxResource)
    parse(typeclassLines.iterator, capabilityLines.iterator, syntaxLines.iterator) match {
      case Right(index)  => index
      case Left(message) => throw new IllegalStateException(message)
    }
  }

  def parse(
      typeclassRows: Iterator[String],
      capabilityRows: Iterator[String],
      syntaxRows: Iterator[String]
  ): Either[String, CatsIndex] =
    for {
      typeclassList <- parseTable(typeclassesResource, typeclassRows)(parseTypeclassRow)
      capabilityList <- parseTable(capabilitiesResource, capabilityRows)(parseCapabilityRow)
      syntaxList <- parseTable(syntaxResource, syntaxRows)(parseSyntaxRow)
    } yield build(typeclassList, capabilityList, syntaxList)

  private def build(
      typeclassList: List[CatsTypeclass],
      capabilityList: List[Capability],
      syntaxList: List[(Symbol, Symbol, Symbol)]
  ): CatsIndex = {
    val typeclassMap = typeclassList.map(tc => tc.symbol -> tc).toMap
    val capabilitiesByTypeclass = capabilityList.groupBy(_.typeclass)
    val capabilitiesByOwnerMethod = capabilityList
      .groupBy(capability => (capability.owner, capability.method))
      .view
      .mapValues(_.sortBy(_.typeclass.value))
      .toMap

    val syntaxMap = syntaxList
      .flatMap { case (syntaxMethod, owner, method) =>
        capabilitiesByOwnerMethod
          .get((owner, method))
          .flatMap(_.headOption)
          .map(syntaxMethod -> _)
      }
      .toMap

    new CatsIndex(typeclassMap, capabilitiesByTypeclass, syntaxMap)
  }

  private def readResourceLines(resource: String): List[String] = {
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(throw new IllegalStateException(s"missing classpath resource: $resource"))
    try {
      val text = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      val lines = text.split("\n", -1).toList
      if (lines.lastOption.contains("")) lines.init else lines
    } finally stream.close()
  }

  /** Parses `#`-prefixed-comment-skipping, tab-separated data rows out of
    * `lines`, threading the 1-based line number of the underlying resource
    * into every error so a malformed artifact fails loudly with a precise
    * location.
    */
  private def parseTable[A](resource: String, lines: Iterator[String])(
      build: List[String] => Either[String, A]
  ): Either[String, List[A]] = {
    val numbered = lines.zipWithIndex

    @annotation.tailrec
    def loop(acc: List[A]): Either[String, List[A]] =
      if (!numbered.hasNext) Right(acc.reverse)
      else {
        val (line, index) = numbered.next()
        val lineNumber = index + 1
        if (line.startsWith("#")) loop(acc)
        else
          build(line.split("\t", -1).toList) match {
            case Right(a)   => loop(a :: acc)
            case Left(error) => Left(s"$resource:$lineNumber: $error")
          }
      }

    loop(Nil)
  }

  private def parseTypeclassRow(cells: List[String]): Either[String, CatsTypeclass] =
    cells match {
      case List(symbol, parents, kindToken, typeParams, depth, renderName, importPath, public) =>
        for {
          kind <- KindShape.parse(kindToken).toRight(s"invalid kind: $kindToken")
          typeParamCount <- parseInt(typeParams, "typeParams")
          depthValue <- parseInt(depth, "depth")
          isPublic <- parseBoolean(public, "public")
        } yield CatsTypeclass(
          Symbol(symbol),
          parseSymbolList(parents),
          kind,
          typeParamCount,
          depthValue,
          renderName,
          importPath,
          isPublic
        )
      case other => Left(s"expected 8 columns, got ${other.size}")
    }

  private def parseCapabilityRow(cells: List[String]): Either[String, Capability] =
    cells match {
      case List(typeclass, method, owner, kindToken, derived, arity) =>
        for {
          kind <- KindShape.parse(kindToken).toRight(s"invalid kind: $kindToken")
          isDerived <- parseBoolean(derived, "derived")
          arityValue <- parseInt(arity, "arity")
        } yield Capability(
          Symbol(typeclass),
          Symbol(method),
          Symbol(owner),
          kind,
          isDerived,
          arityValue
        )
      case other => Left(s"expected 6 columns, got ${other.size}")
    }

  private def parseSyntaxRow(cells: List[String]): Either[String, (Symbol, Symbol, Symbol)] =
    cells match {
      case List(syntaxMethod, owner, method, _) =>
        Right((Symbol(syntaxMethod), Symbol(owner), Symbol(method)))
      case other => Left(s"expected 4 columns, got ${other.size}")
    }

  private def parseSymbolList(cell: String): List[Symbol] =
    if (cell.isEmpty) Nil else cell.split(",", -1).toList.map(Symbol(_))

  private def parseInt(cell: String, field: String): Either[String, Int] =
    cell.toIntOption.toRight(s"invalid $field: $cell")

  private def parseBoolean(cell: String, field: String): Either[String, Boolean] =
    cell match {
      case "true"  => Right(true)
      case "false" => Right(false)
      case _       => Left(s"invalid $field: $cell")
    }
}
