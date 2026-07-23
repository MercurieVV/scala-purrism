package fix.opaque

import scala.meta.internal.{semanticdb => s}

/** What the explorer looks for, and how much of it to report.
  *
  * `basicTypes` is the set of underlying types worth protecting -- primitives
  * and near-primitives, whose values are interchangeable by accident and so
  * benefit most from an opaque wrapper.
  *
  * `minClusterSize` is the threshold, not a cap: every cluster whose value-flow
  * closure reaches at least this many nodes is emitted, and every smaller one
  * is dropped. That is what makes the explorer converge -- once the big flows
  * are opaque, the remainder fall below the threshold and a rerun is a no-op --
  * unlike a fixed "top N" count, which always has N more clusters to hand back.
  *
  * `maxSeedsPerType` bounds the cost: one closure is computed per seed, so a
  * codebase with ten thousand Strings would otherwise run ten thousand graph
  * traversals. Seeds are considered in sorted order, so the cap truncates
  * deterministically rather than arbitrarily.
  */
final case class ExplorerConfig(
    basicTypes: List[String] = ExplorerConfig.DefaultBasicTypes,
    minClusterSize: Int = ExplorerConfig.DefaultMinClusterSize,
    maxSeedsPerType: Int = 2000
)

object ExplorerConfig {

  /** Defaults, documented in the driver's `--help`.
    *
    * `java/util/UUID#` is included because it is trivially available and is
    * routinely used as a bare identifier, which is exactly the confusion an
    * opaque type removes.
    */
  val DefaultBasicTypes: List[String] = List(
    "scala/Predef.String#",
    "scala/Int#",
    "scala/Long#",
    "scala/Double#",
    "scala/Boolean#",
    "java/util/UUID#"
  )

  /** Below this a cluster is too small to be worth an opaque type. Also the
    * convergence point: a rerun over a codebase whose larger flows are already
    * opaque finds only sub-threshold clusters and emits nothing.
    */
  val DefaultMinClusterSize: Int = 4

  val default: ExplorerConfig = ExplorerConfig()
}

/** One ranked candidate: a value-flow cluster and the spec that would convert
  * it.
  */
final case class OpaqueCandidate(
    name: String,
    underlying: String,
    definitionFile: String,
    seeds: List[String],
    members: List[Node],
    owner: String
) {
  def size: Int = members.size

  def spec: fix.OpaqueTypeSpec =
    fix.OpaqueTypeSpec(
      name = name,
      underlying = underlying,
      definitionFile = definitionFile,
      seeds = seeds,
      widen = Nil
    )

  /** One line of the human-readable ranking. */
  def ranking(position: Int): String =
    f"$position%3d. ${size}%5d nodes  $name%-24s $owner"
}

/** Picks opaque-type candidates out of a compiled codebase, mechanically.
  *
  * There is no naming intelligence and no scoring model here on purpose: a
  * candidate is good exactly in proportion to how much of the program its
  * value-flow closure covers, because that is how much code an opaque type
  * would stop from confusing one String with another. Everything else -- the
  * name, the definition site -- is a mechanical derivation a human is expected
  * to correct before committing.
  *
  * The traversal is the rule's own: `GraphBuilder` for the edges and `Closure`
  * for the reachable set, so a candidate's reported size is exactly the set
  * `PropagateOpaqueType` would rewrite.
  */
object OpaqueCandidateExplorer {

  /** Names that are never worth seeding from: compiler-generated members and
    * accessors whose name says nothing about the domain.
    */
  private val UninterestingNames: Set[String] =
    Set("apply", "unapply", "copy", "toString", "productPrefix", "value", "_1")

  def explore(
      index: SemanticdbIndex,
      facts: Facts,
      config: ExplorerConfig = ExplorerConfig.default
  ): List[OpaqueCandidate] = {
    val ranked = config.basicTypes.flatMap(clustersFor(index, facts, config, _))

    // Bigger closure first; ties broken by the cluster's first seed so two runs
    // over the same payload emit the same list in the same order.
    val ordered = ranked.sortBy(cluster =>
      (-cluster.members.size, cluster.seeds.headOption.getOrElse(""))
    )

    // Every cluster over the threshold is kept; `clustersFor` already dropped
    // the sub-threshold ones. No count cap -- the threshold is the only knob,
    // so the emitted set is exactly "all flows still worth an opaque type".
    nameAll(index, dropSubsumed(ordered))
  }

  /** A cluster before it has been named. */
  private final case class Cluster(
      underlying: String,
      seeds: List[String],
      members: List[Node]
  )

  private def clustersFor(
      index: SemanticdbIndex,
      facts: Facts,
      config: ExplorerConfig,
      underlying: String
  ): List[Cluster] = {
    val canonical = SemanticdbIndex.canonicalType(underlying)
    val seeds =
      seedSymbols(index, facts, canonical).take(config.maxSeedsPerType)

    val closures = seeds.map { seed =>
      seed -> Closure
        .compute(Set(Node(seed, TypePath.root)), facts, canonical)
        .members
    }

    closures
      .filter(_._2.size >= config.minClusterSize)
      // Seeds that reach the same set of nodes describe one cluster, not
      // several: reporting each separately would fill the whole top-N with
      // aliases of a single case-class field.
      .groupBy(_._2)
      .map { case (members, group) =>
        Cluster(underlying, group.map(_._1).sorted, members)
      }
      .toList
  }

  /** Value symbols of the wanted type that are ours to rewrite. */
  private def seedSymbols(
      index: SemanticdbIndex,
      facts: Facts,
      canonicalType: String
  ): List[String] =
    index.symbolInfo.keys.toList.sorted.filter { symbol =>
      !symbol.startsWith("local") && !symbol.contains("#local") &&
      isValue(index, symbol) &&
      !UninterestingNames.contains(displayName(symbol)) &&
      facts.origin(symbol) == Origin.Project &&
      facts.typeAt(Node(symbol, TypePath.root)).contains(canonicalType)
    }

  private def isValue(index: SemanticdbIndex, symbol: String): Boolean =
    index.symbolInfo.get(symbol).exists { info =>
      info.kind match {
        case s.SymbolInformation.Kind.METHOD | s.SymbolInformation.Kind.FIELD |
            s.SymbolInformation.Kind.PARAMETER =>
          true
        case _ => false
      }
    }

  /** Drop a cluster wholly contained in a larger one.
    *
    * Two seeds can reach different node sets and still describe one value: a
    * field downstream of another sees only the tail of the flow. Emitting both
    * would spend two of N slots on one opaque type.
    */
  private def dropSubsumed(clusters: List[Cluster]): List[Cluster] = {
    val sets = clusters.map(_.members.toSet)
    clusters.zipWithIndex
      .filterNot { case (_, index) =>
        sets.zipWithIndex.exists { case (other, otherIndex) =>
          otherIndex != index &&
          sets(index).subsetOf(other) &&
          // On an exact tie keep the earlier one, so the relation stays a strict
          // order and two mutually-equal clusters do not delete each other.
          (other.size > sets(index).size || otherIndex < index)
        }
      }
      .map(_._1)
  }

  private def nameAll(
      index: SemanticdbIndex,
      clusters: List[Cluster]
  ): List[OpaqueCandidate] = {
    // Seeded with every type name already in the codebase, not just the names
    // this run hands out. Deriving `State` for a cluster when an `opaque type
    // State` already exists would either fail to emit a definition (the rule's
    // `definesType` guard skips it) and silently retype the field to that
    // unrelated existing opaque, or -- once placed elsewhere -- collide at
    // compile time. Reserving them up front makes discovery pick `State2`
    // instead, the same way a human renaming would.
    val taken = scala.collection.mutable.Set.from(existingTypeNames(index))
    clusters.map { cluster =>
      val name = freeName(deriveName(cluster.members), taken)
      taken += name
      val owner = dominantOwner(index, cluster.members)
      OpaqueCandidate(
        name = name,
        underlying = cluster.underlying,
        definitionFile = "",
        seeds = cluster.seeds,
        members = cluster.members,
        owner = owner
      )
    }
  }

  /** The first of `base`, `base2`, `base3`... not already taken. */
  private def freeName(base: String, taken: collection.Set[String]): String =
    if (!taken.contains(base)) base
    else
      Iterator
        .from(2)
        .map(suffix => s"$base$suffix")
        .find(!taken.contains(_))
        .get

  /** Names of every type already declared in the analysed sources -- classes,
    * traits, opaque types and type aliases, all of which reach SemanticDB with
    * a `#` descriptor. A generated opaque type must not reuse one of these.
    */
  def existingTypeNames(index: SemanticdbIndex): Set[String] =
    index.symbolInfo.keys.iterator
      .filter(_.endsWith("#"))
      .map(displayName)
      .filter(_.nonEmpty)
      .toSet

  /** The cluster's name: its most frequent member name, capitalized.
    *
    * Ties go to the alphabetically first name so the output does not depend on
    * map iteration order.
    */
  def deriveName(members: List[Node]): String = {
    val names = members
      .map(node => displayName(node.symbol))
      .filter(name => name.nonEmpty && name.head.isLetter)
    val chosen = names
      .groupBy(identity)
      .toList
      .sortBy { case (name, occurrences) => (-occurrences.size, name) }
      .headOption
      .map(_._1)
      .getOrElse("Value")
    chosen.head.toUpper +: chosen.tail
  }

  /** The type or package that owns most of the cluster. */
  def dominantOwner(index: SemanticdbIndex, members: List[Node]): String =
    members
      .map(node => enclosingTypeOf(index, node.symbol))
      .filter(_.nonEmpty)
      .groupBy(identity)
      .toList
      .sortBy { case (owner, occurrences) => (-occurrences.size, owner) }
      .headOption
      .map(_._1)
      .getOrElse("")

  /** Where the opaque type definition is written.
    *
    * Nearest enclosing package object if there is one, otherwise the file that
    * defines the cluster's dominant owner -- where its companion already lives.
    *
    * This is deliberately one replaceable function. Placement is the most
    * judgement-laden part of a generated spec and is expected to become
    * configurable; keeping it here means that change touches nothing else.
    */
  def placeDefinition(
      index: SemanticdbIndex,
      candidate: OpaqueCandidate
  ): String = {
    val owner = candidate.owner

    val packageObject =
      packagePrefixes(owner).flatMap { prefix =>
        index.definingUri.get(s"${prefix}package.")
      }.headOption

    val ownerFile = index.definingUri.get(owner)

    val anyMemberFile = candidate.members.iterator
      .flatMap(node => index.definingUri.get(node.symbol))
      .nextOption()

    packageObject.orElse(ownerFile).orElse(anyMemberFile).getOrElse("")
  }

  def withPlacement(
      index: SemanticdbIndex,
      candidates: List[OpaqueCandidate]
  ): List[OpaqueCandidate] =
    candidates.map(candidate =>
      candidate.copy(definitionFile = placeDefinition(index, candidate))
    )

  /** Package prefixes of a symbol, deepest first. */
  private def packagePrefixes(symbol: String): List[String] = {
    val packageSegments = segments(symbol).takeWhile(_.endsWith("/"))
    packageSegments.inits.toList
      .filter(_.nonEmpty)
      .map(_.mkString)
  }

  /** The symbol's immediate owner: everything but its last descriptor. */
  def ownerOf(symbol: String): String = {
    val parts = segments(symbol)
    if (parts.length <= 1) ""
    else parts.init.mkString
  }

  /** The enclosing class, trait or object of a symbol; its package otherwise.
    *
    * A SemanticDB symbol cannot be split on punctuation alone -- an object and
    * a val both end in `.` -- so the owner chain is walked outward and each
    * prefix checked against the symbol table for a type-like kind. A parameter
    * therefore reports the class its method lives in, not the method.
    */
  def enclosingTypeOf(index: SemanticdbIndex, symbol: String): String = {
    import s.SymbolInformation.Kind._
    def isType(candidate: String): Boolean =
      index.symbolInfo.get(candidate).exists { info =>
        info.kind match {
          case CLASS | OBJECT | TRAIT | INTERFACE => true
          case _                                  => false
        }
      }
    def loop(current: String): String = {
      val owner = ownerOf(current)
      if (owner.isEmpty) ""
      else if (isType(owner) || owner.endsWith("/")) owner
      else loop(owner)
    }
    loop(symbol)
  }

  /** The last segment of a symbol, stripped of SemanticDB punctuation. */
  def displayName(symbol: String): String =
    segments(symbol).lastOption
      .map { last =>
        if (last.startsWith("(") && last.endsWith(")"))
          last.substring(1, last.length - 1)
        else
          last
            .stripSuffix(".")
            .stripSuffix("#")
            .stripSuffix("/")
            .stripSuffix("()")
            .stripSuffix("`")
            .stripPrefix("`")
      }
      .getOrElse("")

  /** Split a SemanticDB symbol into its descriptors.
    *
    * Segments end at `/`, `.` or `#` outside brackets, or at a trailing
    * parameter/type-parameter group, so `a/b/C#find().(id)` reads as `a/`,
    * `b/`, `C#`, `find().`, `(id)`.
    */
  def segments(symbol: String): List[String] = {
    val out = List.newBuilder[String]
    var start = 0
    var depth = 0
    var index = 0
    while (index < symbol.length) {
      val char = symbol.charAt(index)
      if (char == '(' || char == '[') depth += 1
      else if (char == ')' || char == ']') {
        depth -= 1
        if (depth == 0 && index + 1 == symbol.length) {
          out += symbol.substring(start, index + 1)
          start = index + 1
        }
      } else if (depth == 0 && (char == '/' || char == '.' || char == '#')) {
        out += symbol.substring(start, index + 1)
        start = index + 1
      }
      index += 1
    }
    if (start < symbol.length) out += symbol.substring(start)
    out.result()
  }

  /** The candidates as a HOCON fragment, pasteable into `.scalafix.conf`. */
  def renderHocon(candidates: List[OpaqueCandidate]): String = {
    def quote(value: String): String =
      "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    val entries = candidates.map { candidate =>
      val seeds = candidate.seeds
        .map(seed => s"      ${quote(seed)}")
        .mkString(
          "[\n",
          "\n",
          "\n    ]"
        )
      s"""  {
         |    name = ${quote(candidate.name)}
         |    underlying = ${quote(candidate.underlying)}
         |    definitionFile = ${quote(candidate.definitionFile)}
         |    seeds = $seeds
         |    widen = []
         |  }""".stripMargin
    }

    s"""PropagateOpaqueType.types = [
       |${entries.mkString("\n")}
       |]
       |""".stripMargin
  }

  /** How many candidates landed at each closure size, largest first.
    *
    * One line per distinct size -- "cluster size 5 (10 of them)" -- so a run
    * shows at a glance what the threshold let through and where it cut.
    */
  def renderSizeHistogram(candidates: List[OpaqueCandidate]): String =
    if (candidates.isEmpty) "no clusters over the size threshold"
    else
      candidates
        .groupBy(_.size)
        .toList
        .sortBy { case (size, _) => -size }
        .map { case (size, group) =>
          f"cluster size $size%3d (${group.size}%d of them)"
        }
        .mkString("\n")

  /** The ranking, for a human to eyeball before pasting anything. */
  def renderRanking(candidates: List[OpaqueCandidate]): String =
    (Seq(f"""${"#"}%3s ${"nodes"}%5s  ${"name"}%-24s owner""") ++
      candidates.zipWithIndex.map { case (candidate, index) =>
        candidate.ranking(index + 1)
      }).mkString("\n")
}
