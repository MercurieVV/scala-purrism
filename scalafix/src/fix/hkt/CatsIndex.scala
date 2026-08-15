package fix.hkt

import java.nio.charset.StandardCharsets

import scalafix.v1.Symbol

/** In-memory, queryable Cats capability index, loaded from the checked-in TSV
  * artifacts under `scalafix/resources/cats-index/` (see
  * `docs/design/PreferPolymorphicTypeclasses.md`, item 2 and item 7).
  */
final class CatsIndex private (
    val typeclasses: Map[Symbol, CatsTypeclass],
    val capabilities: Map[Symbol, List[Capability]],
    val syntax: Map[Symbol, Capability],
    val stdlib: Map[Symbol, List[StdlibEntry]],
    private val syntaxImports: Map[Symbol, String],
    val elementRules: Map[Symbol, ElementRule],
    private val exitMethods: Set[Symbol]
) {

  /** Whether this capability's result leaves the abstracted type constructor.
    *
    * `Functor#map` returns `F[B]`; `Foldable#toList` returns `List[A]`. The
    * operations chained onto the second are operations on a `List`, whatever
    * `F` turns out to be, so they are not constraints on the widening -- which
    * is what lets `xs.toList.zipWithIndex.map(f)` widen `xs` to `S[_]:
    * Foldable` while `zipWithIndex` stays exactly where it is.
    *
    * Read from the generated `exits` column, so it follows Cats rather than a
    * list of method names someone maintains here.
    */
  def exitsConstructor(method: Symbol): Boolean = exitMethods.contains(method)

  /** The element rule for a concrete method, wildcards included. */
  def resolveElement(method: Symbol): Option[ElementRule] =
    elementRules
      .get(method)
      .orElse(
        matchWildcard(method, elementWildcards).map(_._2)
      )

  /** This index with every element rule also readable as an ordinary
    * capability.
    *
    * `UsageAnalyzer` declines a definition on the first call it cannot resolve,
    * so `mkString` would stop the analysis before any constraint set is solved.
    * Read this way the operation resolves to the container capability it still
    * needs -- `Foldable` for `mkString_` -- and the rename and the element
    * constraint are applied afterwards by the rule that asked for this view.
    *
    * The plain index deliberately does *not* resolve them: a rule that renders
    * only a signature would widen the container and leave `mkString` behind,
    * which does not compile.
    */
  def withElementRules: CatsIndex =
    new CatsIndex(
      typeclasses,
      capabilities,
      syntax,
      stdlib ++ elementRules.map { case (concrete, rule) =>
        concrete -> List(
          StdlibEntry(
            concrete,
            StdlibMapping
              .ToCapability(rule.capabilityOwner, rule.capabilityMethod)
          )
        )
      },
      syntaxImports,
      elementRules,
      exitMethods
    )

  private lazy val elementWildcards: List[(String, String, ElementRule)] =
    elementRules.toList.flatMap { case (symbol, rule) =>
      splitMethod(symbol.value).collect {
        case (namespace, name) if namespace.endsWith("*") =>
          (namespace.dropRight(1), name, rule)
      }
    }

  private def matchWildcard[A](
      method: Symbol,
      rules: List[(String, String, A)]
  ): Option[(String, A)] =
    splitMethod(method.value).flatMap { case (namespace, name) =>
      rules.collectFirst {
        case (prefix, suffix, value)
            if name == suffix && namespace.startsWith(prefix) =>
          name -> value
      }
    }

  def this(
      typeclasses: Map[Symbol, CatsTypeclass],
      capabilities: Map[Symbol, List[Capability]],
      syntax: Map[Symbol, Capability],
      stdlib: Map[Symbol, List[StdlibEntry]] = Map.empty
  ) =
    this(
      typeclasses,
      capabilities,
      syntax,
      stdlib,
      Map.empty,
      Map.empty,
      Set.empty
    )

  /** Every capability whose method or owner is `method`, across all
    * typeclasses, in stable order (by typeclass symbol string).
    */
  def providersOf(method: Symbol): List[Capability] =
    capabilities.valuesIterator.flatten
      .filter(capability =>
        capability.method == method || capability.owner == method
      )
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

  /** Whether the index has any theory of this type constructor at all: a Cats
    * capability declared on it, or a `stdlib.tsv` row -- exact or under a
    * wildcard namespace -- for one of its methods.
    *
    * A rule that reports why it did *not* abstract something needs this.
    * Without it the answer "your body has a `var`" is given for
    * `def sum(out: Array[Float], n: Int): Unit`, where the obstacle is not the
    * `var` and no capability was ever within reach. Silence is the honest
    * report for a constructor nothing here knows anything about.
    */
  def knowsConstructor(constructor: Symbol): Boolean = {
    val owner = constructor.value.stripSuffix("#")
    lazy val exact = s"$owner#"
    capabilities.valuesIterator.flatten.exists(
      _.owner.value.startsWith(exact)
    ) ||
    stdlib.keysIterator.exists(_.value.startsWith(exact)) ||
    wildcards.exists { case (prefix, _, _) => owner.startsWith(prefix) }
  }

  def resolveStdlib(method: Symbol): List[StdlibEntry] =
    stdlib.get(method).orElse(byMethodName(method)).getOrElse(Nil)

  /** The wildcard fallback: a row whose owner ends in a star -- the namespace
    * `scala.collection` followed by `*`, then `#filter().` -- answers for every
    * class under that namespace.
    *
    * The compiler resolves `xs.filter` to the *concrete* collection --
    * `scala/collection/immutable/List#filter().` -- and there is no SemanticDB
    * for the standard library on the classpath, so the declaration it overrides
    * cannot be looked up. Without a wildcard the table therefore needs one row
    * per collection class per method, which is a combinatorial list that
    * silently misses whichever pair nobody wrote down. A namespace and a method
    * name is the fact actually being stated.
    */
  private def byMethodName(method: Symbol): Option[List[StdlibEntry]] =
    splitMethod(method.value).flatMap { case (namespace, name) =>
      wildcards.collectFirst {
        case (prefix, suffix, entries)
            if name == suffix && namespace.startsWith(prefix) =>
          entries
      }
    }

  /** Rows whose owner ends in `*`, as (namespace prefix, method name, entries).
    */
  private lazy val wildcards: List[(String, String, List[StdlibEntry])] =
    stdlib.toList.flatMap { case (symbol, entries) =>
      splitMethod(symbol.value).collect {
        case (namespace, name) if namespace.endsWith("*") =>
          (namespace.dropRight(1), name, entries)
      }
    }

  /** Splits `<owner>#<name>(...)` into its owner and its method name. */
  private def splitMethod(value: String): Option[(String, String)] = {
    val hash = value.indexOf('#')
    val paren = value.indexOf('(', hash + 1)
    Option.when(hash > 0 && paren > hash)(
      value.substring(0, hash) -> value.substring(hash + 1, paren)
    )
  }

  /** The syntax wildcard import for a syntax method or its resolved primitive
    * owner.
    */
  def syntaxImport(method: Symbol): Option[String] = syntaxImports.get(method)

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
    val stdlibLines = readResourceLines(stdlibResource)
    parse(
      typeclassLines.iterator,
      capabilityLines.iterator,
      syntaxLines.iterator,
      stdlibLines.iterator
    ) match {
      case Right(index)  => index
      case Left(message) => throw new IllegalStateException(message)
    }
  }

  def parse(
      typeclassRows: Iterator[String],
      capabilityRows: Iterator[String],
      syntaxRows: Iterator[String],
      stdlibRows: Iterator[String] = Iterator.empty
  ): Either[String, CatsIndex] =
    for {
      typeclassList <- parseTable(typeclassesResource, typeclassRows)(
        parseTypeclassRow
      )
      capabilityRowList <- parseTable(capabilitiesResource, capabilityRows)(
        parseCapabilityRow
      )
      capabilityList = capabilityRowList.map(_._1)
      syntaxList <- parseTable(syntaxResource, syntaxRows)(parseSyntaxRow)
      capabilityRoots = capabilityList.iterator
        .map(capability => capability.owner -> capability.method)
        .toSet
      stdlibList <- parseTable(stdlibResource, stdlibRows)(
        parseStdlibRow(_, capabilityRoots)
      )
    } yield build(typeclassList, capabilityRowList, syntaxList, stdlibList)

  private def build(
      typeclassList: List[CatsTypeclass],
      capabilityRowList: List[(Capability, Boolean)],
      syntaxList: List[(Symbol, Symbol, Symbol, String)],
      stdlibRowList: List[StdlibRow]
  ): CatsIndex = {
    val capabilityList = capabilityRowList.map(_._1)
    // Keyed by the override-chain root, which is what `RequiredOp.method`
    // carries: `xs.toList` resolves to `cats/Foldable#toList().` whichever
    // typeclass row it was read from.
    val exitMethods = capabilityRowList.iterator
      .filter(_._2)
      .map(_._1.owner)
      .toSet -- capabilityRowList.iterator
      .filterNot(_._2)
      .map(_._1.owner)
    val stdlibList = stdlibRowList.collect { case StdlibRow.Entry(e) => e }
    val elementList = stdlibRowList.collect { case StdlibRow.Element(r) => r }
    val typeclassMap = typeclassList.map(tc => tc.symbol -> tc).toMap
    val capabilitiesByTypeclass = capabilityList.groupBy(_.typeclass)
    val capabilitiesByOwnerMethod = capabilityList
      .groupBy(capability => (capability.owner, capability.method))
      .view
      .mapValues(_.sortBy(_.typeclass.value))
      .toMap

    val syntaxMap = syntaxList.flatMap {
      case (syntaxMethod, owner, method, _) =>
        capabilitiesByOwnerMethod
          .get((owner, method))
          .flatMap(_.headOption)
          .map(syntaxMethod -> _)
    }.toMap

    val exactSyntaxImports = syntaxList.map {
      case (syntaxMethod, _, _, importPath) =>
        syntaxMethod -> importPath
    }.toMap
    val resolvedSyntaxImports = syntaxList
      .flatMap { entry =>
        val (_, owner, method, _) = entry
        List(owner, method).distinct.map(_ -> entry)
      }
      .groupBy(_._1)
      .view
      .mapValues { entries =>
        entries
          .map(_._2)
          .sortBy { case (syntaxMethod, _, method, importPath) =>
            val directOwner =
              syntaxMethod.value.replace(".Ops#", "#") == method.value
            (if (directOwner) 0 else 1, syntaxMethod.value, importPath)
          }
          .head
          ._4
      }
      .toMap

    val stdlibMap = stdlibList
      .groupBy(_.concreteMethod)
      .view
      .mapValues(_.sortBy(stdlibSortKey))
      .toMap

    new CatsIndex(
      typeclassMap,
      capabilitiesByTypeclass,
      syntaxMap,
      stdlibMap,
      exactSyntaxImports ++ resolvedSyntaxImports,
      elementList.map(rule => rule.concreteMethod -> rule).toMap,
      exitMethods
    )
  }

  /** A parsed stdlib row: an ordinary mapping, or an element rule. */
  private sealed trait StdlibRow
  private object StdlibRow {
    final case class Entry(entry: StdlibEntry) extends StdlibRow
    final case class Element(rule: ElementRule) extends StdlibRow
  }

  private def readResourceLines(resource: String): List[String] = {
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(
        throw new IllegalStateException(
          s"missing classpath resource: $resource"
        )
      )
    try {
      val text = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      val lines = text.split("\n", -1).toList
      if (lines.lastOption.contains("")) lines.init else lines
    } finally stream.close()
  }

  /** Parses `#`-prefixed-comment-skipping, tab-separated data rows out of
    * `lines`, threading the 1-based line number of the underlying resource into
    * every error so a malformed artifact fails loudly with a precise location.
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
            case Right(a)    => loop(a :: acc)
            case Left(error) => Left(s"$resource:$lineNumber: $error")
          }
      }

    loop(Nil)
  }

  private def parseTypeclassRow(
      cells: List[String]
  ): Either[String, CatsTypeclass] =
    cells match {
      case List(
            symbol,
            parents,
            kindToken,
            typeParams,
            depth,
            renderName,
            importPath,
            public
          ) =>
        for {
          kind <- KindShape
            .parse(kindToken)
            .toRight(s"invalid kind: $kindToken")
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

  private def parseCapabilityRow(
      cells: List[String]
  ): Either[String, (Capability, Boolean)] =
    cells match {
      case List(typeclass, method, owner, kindToken, derived, arity, exits) =>
        for {
          kind <- KindShape
            .parse(kindToken)
            .toRight(s"invalid kind: $kindToken")
          isDerived <- parseBoolean(derived, "derived")
          arityValue <- parseInt(arity, "arity")
          leaves <- parseBoolean(exits, "exits")
        } yield (
          Capability(
            Symbol(typeclass),
            Symbol(method),
            Symbol(owner),
            kind,
            isDerived,
            arityValue
          ),
          leaves
        )
      case other => Left(s"expected 7 columns, got ${other.size}")
    }

  private def parseSyntaxRow(
      cells: List[String]
  ): Either[String, (Symbol, Symbol, Symbol, String)] =
    cells match {
      case List(syntaxMethod, owner, method, importPath) =>
        Right((Symbol(syntaxMethod), Symbol(owner), Symbol(method), importPath))
      case other => Left(s"expected 4 columns, got ${other.size}")
    }

  private def parseStdlibRow(
      cells: List[String],
      capabilityRoots: Set[(Symbol, Symbol)]
  ): Either[String, StdlibRow] =
    cells match {
      // An element rule carries two extra columns: the Cats spelling to rename
      // the call to, and the typeclass its meaning comes from.
      case List(
            concreteMethod,
            "element",
            owner,
            method,
            renameTo,
            element,
            _
          ) =>
        val target = Symbol(owner) -> Symbol(method)
        if (concreteMethod.isEmpty) Left("concreteMethod must not be empty")
        else if (renameTo.isEmpty) Left("element rename must not be empty")
        else if (element.isEmpty) Left("element constraint must not be empty")
        else if (!capabilityRoots(target))
          Left(s"unknown capability target: $owner / $method")
        else
          Right(
            StdlibRow.Element(
              ElementRule(
                Symbol(concreteMethod),
                renameTo,
                target._1,
                target._2,
                Symbol(element)
              )
            )
          )
      case List(concreteMethod, "capability", owner, method, _) =>
        val target = Symbol(owner) -> Symbol(method)
        if (concreteMethod.isEmpty) Left("concreteMethod must not be empty")
        else if (owner.isEmpty) Left("capability owner must not be empty")
        else if (method.isEmpty) Left("capability method must not be empty")
        else if (!capabilityRoots(target))
          Left(s"unknown capability target: $owner / $method")
        else
          Right(
            StdlibRow.Entry(
              StdlibEntry(
                Symbol(concreteMethod),
                StdlibMapping.ToCapability(target._1, target._2)
              )
            )
          )
      case List(concreteMethod, "decline", owner, reason, _) =>
        if (concreteMethod.isEmpty) Left("concreteMethod must not be empty")
        else if (owner.nonEmpty) Left("decline owner must be empty")
        else if (!stdlibDeclineReasons(reason))
          Left(s"invalid decline reason: $reason")
        else
          Right(
            StdlibRow.Entry(
              StdlibEntry(
                Symbol(concreteMethod),
                StdlibMapping.ToDecline(reason)
              )
            )
          )
      case List(_, kind, _, _, _) =>
        Left(s"invalid stdlib kind: $kind")
      case List(_, kind, _, _, _, _, _) =>
        Left(s"invalid stdlib kind for a 7-column row: $kind")
      case other => Left(s"expected 5 or 7 columns, got ${other.size}")
    }

  private val stdlibDeclineReasons: Set[String] =
    Set(
      "ConcreteConstructorMatch",
      "OrderOrIndexSpecific",
      "UnsafeBody"
    )

  private def stdlibSortKey(entry: StdlibEntry): (String, String, String) =
    entry.mapping match {
      case StdlibMapping.ToCapability(owner, method) =>
        ("capability", owner.value, method.value)
      case StdlibMapping.ToDecline(reason) =>
        ("decline", "", reason)
    }

  private def parseSymbolList(cell: String): List[Symbol] =
    if (cell.isEmpty) Nil else cell.split(",", -1).toList.map(Symbol(_))

  private def parseInt(cell: String, field: String): Either[String, Int] =
    cell.toIntOption.toRight(s"invalid $field: $cell")

  private def parseBoolean(
      cell: String,
      field: String
  ): Either[String, Boolean] =
    cell match {
      case "true"  => Right(true)
      case "false" => Right(false)
      case _       => Left(s"invalid $field: $cell")
    }
}
