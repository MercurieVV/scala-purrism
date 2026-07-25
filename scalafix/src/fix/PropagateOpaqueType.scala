package fix

import java.nio.file.Path

import scala.annotation.nowarn
import scala.meta._

// Imported by name rather than with a wildcard: metaconfig also defines Conf,
// Input and Position, which would collide with scala.meta's.
import metaconfig.ConfDecoder
import metaconfig.Configured
import scalafix.v1._

import fix.opaque._

/** One opaque type to introduce, and where its propagation starts.
  *
  * Seeds are exact SemanticDB symbols rather than names, so the rule converts a
  * specific field's flow instead of everything that happens to be spelled
  * alike. Any of a case-class field's four symbols will do -- getter,
  * constructor, `apply` or `copy` -- since they are linked as aliases.
  */
final case class OpaqueTypeSpec(
    name: String = "",
    underlying: String = "scala/Predef.String#",
    definitionFile: String = "",
    seeds: List[String] = Nil,
    widen: List[String] = Nil
)

object OpaqueTypeSpec {
  val default: OpaqueTypeSpec = OpaqueTypeSpec()

  // Written by hand rather than with `generic.deriveDecoder`: metaconfig's
  // derivation is a Scala 2 macro and only metaconfig_2.13 is on the classpath,
  // so it cannot be expanded from Scala 3.
  implicit val decoder: ConfDecoder[OpaqueTypeSpec] =
    ConfDecoder.from { conf =>
      // `Configured` is applicative, not monadic -- no flatMap -- so the fields
      // are threaded with andThen rather than a for-comprehension.
      conf.getOrElse("name")(default.name).andThen { name =>
        conf.getOrElse("underlying")(default.underlying).andThen { underlying =>
          conf.getOrElse("definitionFile")(default.definitionFile).andThen {
            definitionFile =>
              conf.getOrElse("seeds")(default.seeds).andThen { seeds =>
                conf.getOrElse("widen")(default.widen).map { widen =>
                  OpaqueTypeSpec(
                    name,
                    underlying,
                    definitionFile,
                    seeds,
                    widen
                  )
                }
              }
          }
        }
      }
    }
}

/** Triggers `ExploreOpaques`'s ranking logic from inside the rule itself,
  * instead of requiring a separate `mill scalafix.explorer.runMain` pass whose
  * output is then hand-pasted into `types`.
  *
  * `serialize = false` (the default) folds every discovered candidate into the
  * same `types` list the rule already applies in one `fix()` pass -- exactly
  * how several hand-written specs are applied today, so two clusters that touch
  * the same declarations merge (or conflict) the same way hand-written ones
  * would.
  *
  * `serialize = true` instead applies each discovered candidate as its own
  * nested `PropagateOpaqueType` invocation via [[CandidateApplier]], writing to
  * disk between candidates the way `ExploreOpaques`'s driver does. That avoids
  * same-file patch conflicts between discovered clusters, at the cost of only
  * the first cluster touching a given file landing per run -- the rest report
  * the file's payload as stale (see `PropagateOpaqueType.fix`) and need a
  * recompile-and-rerun to apply, same caveat `ExploreOpaques` prints.
  */
final case class AutoDiscoverConfig(
    enabled: Boolean = false,
    minClusterSize: Int = ExplorerConfig.DefaultMinClusterSize,
    basicTypes: List[String] = ExplorerConfig.DefaultBasicTypes,
    serialize: Boolean = false
)

object AutoDiscoverConfig {
  val default: AutoDiscoverConfig = AutoDiscoverConfig()

  implicit val decoder: ConfDecoder[AutoDiscoverConfig] =
    ConfDecoder.from { conf =>
      conf.getOrElse("enabled")(default.enabled).andThen { enabled =>
        conf.getOrElse("minClusterSize")(default.minClusterSize).andThen {
          minClusterSize =>
            conf.getOrElse("basicTypes")(default.basicTypes).andThen {
              basicTypes =>
                conf.getOrElse("serialize")(default.serialize).map {
                  serialize =>
                    AutoDiscoverConfig(
                      enabled,
                      minClusterSize,
                      basicTypes,
                      serialize
                    )
                }
            }
        }
      }
    }
}

final case class PropagateOpaqueTypeConfig(
    types: List[OpaqueTypeSpec] = Nil,
    debug: Boolean = false,
    autoDiscover: AutoDiscoverConfig = AutoDiscoverConfig.default
)

object PropagateOpaqueTypeConfig {
  val default: PropagateOpaqueTypeConfig = PropagateOpaqueTypeConfig()
  implicit val decoder: ConfDecoder[PropagateOpaqueTypeConfig] =
    ConfDecoder.from { conf =>
      conf.getOrElse("types")(default.types).andThen { types =>
        conf.getOrElse("debug")(default.debug).andThen { debug =>
          conf.getOrElse("autoDiscover")(default.autoDiscover).map {
            autoDiscover =>
              PropagateOpaqueTypeConfig(types, debug, autoDiscover)
          }
        }
      }
    }
}

/** A merge point is guidance, not a defect, so it is reported as a warning.
  *
  * `Diagnostic` defaults to `LintSeverity.Error`, and scalafix withholds a
  * rule's patches when it emits lint errors -- which silently turned the whole
  * rewrite into a no-op for any file that also reported a merge point.
  */
final case class MergePointDiagnostic(
    override val position: scala.meta.inputs.Position,
    override val message: String
) extends Diagnostic {
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Warning
}

/** Replaces a value type with an opaque type and follows the value wherever it
  * flows -- through parameters, fields, returns, container type arguments and
  * Kleisli input tuples -- stopping where the value is created or crosses into
  * an API whose signature is not ours to change.
  *
  * The analysis is whole-program, but patches are emitted per file: the closure
  * carries SemanticDB symbol strings and positions, never trees, and every
  * patch is anchored on `doc.tree`. That separation is deliberate. Anchoring a
  * patch on a tree parsed from anything other than `doc.input` writes at
  * offsets that only coincide by luck, which is how the older
  * `OpaqueTypePropagation` rule could corrupt a file outright.
  */
final class PropagateOpaqueType(
    config: PropagateOpaqueTypeConfig,
    classpath: List[Path],
    precomputed: Option[PropagateOpaqueType.IndexBundle] = None
) extends SemanticRule("PropagateOpaqueType") {

  def this() = this(PropagateOpaqueTypeConfig.default, Nil)

  override def withConfiguration(
      configuration: Configuration
  ): Configured[Rule] =
    configuration.conf
      .getOrElse("PropagateOpaqueType")(PropagateOpaqueTypeConfig.default)
      .andThen { parsed =>
        // `--semanticdb-targetroots` is prepended to the scalac classpath by
        // the CLI, so the payload location arrives here for free.
        val scalacClasspath = configuration.scalacClasspath.map(_.toNIO)
        println(s"DEBUG scalacClasspath: ${scalacClasspath.mkString(", ")}")

        if (!parsed.autoDiscover.enabled)
          Configured.ok(new PropagateOpaqueType(parsed, scalacClasspath))
        else if (parsed.autoDiscover.serialize)
          // This module deliberately excludes scalafix-cli/-interfaces from
          // its own dependencies -- see build.mill's `explorer` module comment
          // -- so a published rule jar never drags the CLI in as a transitive
          // dependency of `scalafixDependencies`. That is exactly what a
          // recompile-between-candidates apply needs (it runs each candidate
          // as its own nested Scalafix invocation), so it can only live in
          // the `explorer` module, not in this rule.
          Configured.error(
            "PropagateOpaqueType.autoDiscover.serialize is not supported " +
              "from inside the rule itself: recompile-between-candidates apply " +
              "needs scalafix-cli, which this module cannot depend on. Run " +
              "`mill scalafix.explorer.runMain fix.opaque.ExploreOpaques " +
              "--target <path>` instead, or set autoDiscover.serialize = false " +
              "to fold discovered candidates into this rule's own single pass."
          )
        else {
          // Built once here and threaded into the instance below, rather than
          // left to the lazy vals: autoDiscover needs the whole-program index
          // and facts before `types` even exists, and a rule built fresh would
          // otherwise redo that same whole-program build a second time.
          val bundle = PropagateOpaqueType.IndexBundle.build(scalacClasspath)
          val discovered = PropagateOpaqueType.discover(
            bundle,
            parsed.autoDiscover,
            parsed.types
          )

          Configured.ok(
            new PropagateOpaqueType(
              parsed.copy(types = parsed.types ++ discovered.map(_.spec)),
              scalacClasspath,
              Some(bundle)
            )
          )
        }
      }

  private lazy val index: SemanticdbIndex =
    precomputed.map(_.index).getOrElse(SemanticdbIndex.load(classpath))

  private lazy val sourceroot: Path =
    precomputed
      .map(_.sourceroot)
      .getOrElse(PropagateOpaqueType.inferSourceroot(index, classpath))

  private lazy val graph: Graph =
    precomputed
      .map(_.graph)
      .getOrElse(new GraphBuilder(index, sourceroot).build())

  private lazy val facts: Facts =
    precomputed.map(_.facts).getOrElse(GraphBuilder.facts(index, graph))

  private lazy val closures: List[(OpaqueTypeSpec, ClosureResult)] =
    config.types.map { spec =>
      val primitive = SemanticdbIndex.canonicalType(spec.underlying)
      val seeds = spec.seeds.map(Node(_, TypePath.root)).toSet
      spec -> Closure.compute(seeds, facts, primitive, spec.widen.toSet)
    }

  /** Documents whose payload no longer matches the file on disk. */
  private lazy val stale: Set[String] =
    index.staleDocuments(sourceroot).toSet

  override def fix(implicit doc: SemanticDocument): Patch = {
    val uri = PropagateOpaqueType.currentUri(index, doc)

    if (config.types.isEmpty) Patch.empty
    // A stale payload describes code that no longer exists, so a closure
    // computed from it would be wrong for this file. Report it and change
    // nothing rather than patch against a stale view.
    else if (uri.exists(stale.contains))
      Patch.lint(
        MergePointDiagnostic(
          doc.tree.pos,
          s"SemanticDB for ${uri.getOrElse("this file")} is out of date, so it " +
            "was left unchanged. Regenerate it and re-run."
        )
      )
    else {
      val declarations = PropagateOpaqueType.declaredTypes(uri)
      if (config.debug) reportDebug(uri, declarations)

      closures.map { case (spec, result) =>
        annotationPatches(spec, result, declarations, uri) +
          boundaryPatches(spec, result, uri) +
          definitionPatch(spec, uri) +
          mergePointDiagnostics(result, declarations, uri)
      }.asPatch
    }
  }

  /** Which closure members this file could and could not place, for `debug`. */
  private def reportDebug(
      uri: Option[String],
      declarations: Map[String, Type]
  ): Unit = {
    val members = closures.flatMap(_._2.members)
    val (matched, unmatched) =
      members.partition(node => declarations.contains(node.symbol))
    println(s"[PropagateOpaqueType] uri=$uri")
    println(s"[PropagateOpaqueType]   declared symbols: ${declarations.size}")
    println(
      s"[PropagateOpaqueType]   matched members : ${matched.map(_.render)}"
    )
    println(
      s"[PropagateOpaqueType]   unmatched       : ${unmatched.map(_.render).take(8)}"
    )
  }

  /** Wrap where the value is created, unwrap where it leaves.
    *
    * Both are found by matching the boundary's recorded position against
    * `doc.tree`. The position comes from the graph builder's own parse of this
    * same file, but the patch is anchored on the node found in `doc.tree` --
    * the builder's tree never escapes into a patch.
    */
  private def boundaryPatches(
      spec: OpaqueTypeSpec,
      result: ClosureResult,
      uri: Option[String]
  )(implicit doc: SemanticDocument): Patch = {
    def here(boundary: Boundary): Boolean = uri.contains(boundary.at.uri)

    val wraps =
      result.genesis.filter(here).filter(_.node.path.indices.isEmpty).flatMap {
        boundary =>
          PropagateOpaqueType.termAt(boundary.at).collect {
            case term if !PropagateOpaqueType.isWrappedWith(term, spec.name) =>
              Patch.addLeft(term, s"${spec.name}(") + Patch.addRight(term, ")")
          }
      }

    val unwraps = result.leaves.filter(here).flatMap { boundary =>
      println(
        s"UNWRAP candidate: ${boundary.node.render} -> ${boundary.counterpart.render} at ${boundary.at}"
      )
      PropagateOpaqueType.termAt(boundary.at).collect {
        case term if !PropagateOpaqueType.isUnwrapped(term) =>
          println(
            s"UNWRAPPING term: $term to ${PropagateOpaqueType.unwrapped(term)}"
          )
          Patch.replaceTree(term, PropagateOpaqueType.unwrapped(term))
      }
    }

    // One expression can feed several parameters; patch each position once.
    (wraps ++ unwraps).distinct.asPatch
  }

  /** Emit the opaque type and its companion, in the one file named by
    * `definitionFile`.
    *
    * Placement is explicit rather than inferred. The older rule scanned sibling
    * files and picked the alphabetically first, which is both non-deterministic
    * under renames and prone to emitting the definition above a `package`
    * clause, where it does not compile.
    */
  private def definitionPatch(spec: OpaqueTypeSpec, uri: Option[String])(
      implicit doc: SemanticDocument
  ): Patch =
    // `definitionFile` may be written as a full sourceroot-relative path or as
    // a trailing fragment of one.
    if (
      spec.definitionFile.isEmpty ||
      !uri.exists(current =>
        current == spec.definitionFile || current.endsWith(spec.definitionFile)
      )
    ) Patch.empty
    else if (PropagateOpaqueType.definesType(doc.tree, spec.name)) Patch.empty
    else {
      val underlying = PropagateOpaqueType.underlyingName(spec.underlying)
      val code =
        s"""opaque type ${spec.name} = $underlying
           |object ${spec.name}:
           |  def apply(value: $underlying): ${spec.name} = value
           |  extension (self: ${spec.name}) def value: $underlying = self
           |""".stripMargin

      PropagateOpaqueType.definitionAnchor(doc.tree) match {
        case Some(Right(lastImport)) =>
          Patch.addRight(lastImport, "\n\n" + code.trim)
        case Some(Left(firstStat)) =>
          Patch.addLeft(firstStat, code.trim + "\n\n")
        case None => Patch.empty
      }
    }

  /** Retype every declaration in this file that the closure covers. */
  private def annotationPatches(
      spec: OpaqueTypeSpec,
      result: ClosureResult,
      declarations: Map[String, Type],
      uri: Option[String]
  )(implicit doc: SemanticDocument): Patch = {
    val declared = result.members.flatMap { node =>
      declarations
        .get(node.symbol)
        .flatMap(PropagateOpaqueType.typeAtPath(_, node.path.indices))
        .filter(_.syntax != spec.name) // already converted; stay idempotent
        .map(Patch.replaceTree(_, spec.name))
    }

    // Nodes standing for a written type argument rather than a declaration --
    // the `local[...]` of a Kleisli reshaping. They are addressed by position,
    // since there is no symbol to look them up by.
    val synthetic = result.members.flatMap { node =>
      PropagateOpaqueType
        .syntheticTypePosition(node.symbol, uri)
        .flatMap(PropagateOpaqueType.typeAt)
        .flatMap(PropagateOpaqueType.typeAtPath(_, node.path.indices))
        .filter(_.syntax != spec.name)
        .map(Patch.replaceTree(_, spec.name))
    }

    (declared ++ synthetic).asPatch
  }

  /** Report a node the closure reached but could not convert, because a value
    * it does not cover also flows in. The message names the symbol to add to
    * `widen` if that other value belongs in the conversion after all.
    */
  private def mergePointDiagnostics(
      result: ClosureResult,
      declarations: Map[String, Type],
      uri: Option[String]
  ): Patch =
    result.mergePoints.flatMap { merge =>
      declarations
        .get(merge.node.symbol)
        .map(tpe => Patch.lint(MergePointDiagnostic(tpe.pos, merge.message)))
    }.asPatch
}

object PropagateOpaqueType {

  /** The whole-program payload `autoDiscover` and the rule's own closures both
    * need, built once and shared between them.
    */
  final case class IndexBundle(
      index: SemanticdbIndex,
      sourceroot: Path,
      graph: Graph,
      facts: Facts
  )

  object IndexBundle {
    def build(classpath: List[Path]): IndexBundle = {
      val index = SemanticdbIndex.load(classpath)
      val sourceroot = inferSourceroot(index, classpath)
      val graph = new GraphBuilder(index, sourceroot).build()
      val facts = GraphBuilder.facts(index, graph)
      IndexBundle(index, sourceroot, graph, facts)
    }
  }

  /** Candidates `ExploreOpaques`'s ranking would find, minus any that collide
    * with a hand-written spec in `manual` -- same name, or a seed a manual spec
    * already claims. Manual specs win: they were written on purpose, discovery
    * is a suggestion.
    */
  def discover(
      bundle: IndexBundle,
      autoDiscover: AutoDiscoverConfig,
      manual: List[OpaqueTypeSpec]
  ): List[OpaqueCandidate] = {
    val claimedNames = manual.map(_.name).toSet
    val claimedSeeds = manual.flatMap(_.seeds).toSet
    val explorerConfig = ExplorerConfig(
      basicTypes = autoDiscover.basicTypes,
      minClusterSize = autoDiscover.minClusterSize
    )
    val candidates = OpaqueCandidateExplorer.withPlacement(
      bundle.index,
      OpaqueCandidateExplorer.explore(
        bundle.index,
        bundle.facts,
        explorerConfig
      )
    )
    val res = candidates.filterNot(candidate =>
      claimedNames.contains(candidate.name) ||
        candidate.seeds.exists(claimedSeeds.contains)
    )
    println("--- DISCOVERED CANDIDATES ---")
    println(OpaqueCandidateExplorer.renderSizeHistogram(res))
    res.foreach(c => println(s"Candidate: ${c.name}, seeds: ${c.seeds}"))
    res
  }

  /** Walk a declared type by type-argument index, matching how `TypePath`
    * addresses SemanticDB signatures. A tuple is indexed like any other type
    * application, so `Kleisli[F, (A, B), C]` reaches `B` at [1, 1].
    */
  @nowarn("cat=deprecation")
  def typeAtPath(tpe: Type, indices: List[Int]): Option[Type] =
    indices match {
      case Nil => Some(tpe)
      case index :: rest =>
        tpe match {
          case Type.Apply(_, args) =>
            args.lift(index).flatMap(typeAtPath(_, rest))
          case Type.Tuple(args) => args.lift(index).flatMap(typeAtPath(_, rest))
          case Type.ByName(inner)      => typeAtPath(inner, indices)
          case Type.Repeated(inner)    => typeAtPath(inner, rest)
          case Type.Annotate(inner, _) => typeAtPath(inner, indices)
          case _                       => None
        }
    }

  /** The term occupying exactly this span in `doc.tree`.
    *
    * Matching the whole span, not just the start, keeps a `Term.Name` from
    * being confused with the `Term.Select` that begins at the same column.
    * Where several nodes share a span the outermost is taken, so a wrap
    * encloses the entire expression.
    */
  def termAt(at: Provenance)(implicit doc: SemanticDocument): Option[Term] =
    doc.tree.collect {
      case term: Term
          if term.pos.startLine == at.startLine &&
            term.pos.startColumn == at.startColumn &&
            term.pos.endLine == at.endLine &&
            term.pos.endColumn == at.endColumn =>
        term
    }.headOption

  @nowarn("cat=deprecation")
  /** The (line, column) a `localinput:<uri>:<line>:<col>` node refers to, when
    * that node belongs to the file being patched.
    *
    * Parsed from the right, because a uri may itself contain colons.
    */
  def syntheticTypePosition(
      symbol: String,
      uri: Option[String]
  ): Option[(Int, Int)] =
    Option
      .when(symbol.startsWith("localinput:"))(symbol.stripPrefix("localinput:"))
      .flatMap { rest =>
        rest.split(':').toList.reverse match {
          case column :: line :: ownerParts =>
            val owner = ownerParts.reverse.mkString(":")
            for {
              current <- uri
              if current == owner || current.endsWith(owner) ||
                owner.endsWith(current)
              parsedLine <- line.toIntOption
              parsedColumn <- column.toIntOption
            } yield (parsedLine, parsedColumn)
          case _ => None
        }
      }

  /** The type starting at this position in `doc.tree`. */
  def typeAt(
      position: (Int, Int)
  )(implicit doc: SemanticDocument): Option[Type] =
    doc.tree.collect {
      case tpe: Type
          if tpe.pos.startLine == position._1 &&
            tpe.pos.startColumn == position._2 =>
        tpe
    }.headOption

  @nowarn("cat=deprecation")
  def isWrappedWith(term: Term, typeName: String): Boolean = term match {
    case Term.Apply.After_4_6_0(Term.Name(name), _) => name == typeName
    case _                                          => false
  }

  def isUnwrapped(term: Term): Boolean = term match {
    case Term.Select(_, Term.Name("value")) => true
    case _                                  => false
  }

  /** The term exactly as it appears in the source.
    *
    * `Tree.syntax` re-prints from the AST, which loses the original formatting:
    * an interpolation like `s"task-${task.number}"` comes back out spread over
    * three lines. Reading the span keeps the author's text untouched.
    */
  def sourceOf(term: Term): String = term.pos.text

  /** `x.value`, parenthesised when the term would not bind tightly enough. */
  def unwrapped(term: Term): String = term match {
    case _: Term.Name | _: Term.Select | _: Term.Apply | _: Lit =>
      s"${sourceOf(term)}.value"
    case _ => s"(${sourceOf(term)}).value"
  }

  /** The short name to write for an underlying type given as a symbol. */
  def underlyingName(symbol: String): String =
    symbol.split('/').last.stripSuffix("#").split('.').last match {
      case ""    => symbol
      case short => short
    }

  def definesType(tree: Tree, name: String): Boolean =
    tree.collect {
      case defn: Defn.Type if defn.name.value == name => ()
      case cls: Defn.Class if cls.name.value == name  => ()
      case obj: Defn.Object if obj.name.value == name => ()
      case trt: Defn.Trait if trt.name.value == name  => ()
    }.nonEmpty

  /** Where to insert a top-level definition: after the last import, else before
    * the first statement -- and always *inside* the package clause.
    */
  @nowarn("cat=deprecation")
  def definitionAnchor(tree: Tree): Option[Either[Stat, Stat]] = {
    val stats = tree match {
      case source: Source =>
        source.stats.flatMap {
          case pkg: Pkg => pkg.stats
          case other    => List(other)
        }
      case pkg: Pkg => pkg.stats
      case _        => Nil
    }
    stats.collect { case imp: Import => imp }.lastOption match {
      case Some(lastImport) => Some(Right(lastImport))
      case None             => stats.headOption.map(Left(_))
    }
  }

  /** Declared types in this file, keyed by SemanticDB symbol.
    *
    * Only positions with a written type appear: an inferred `val` needs no
    * annotation, it follows whatever it was assigned.
    */
  @nowarn("cat=deprecation")
  def declaredTypes(uri: Option[String])(implicit
      doc: SemanticDocument
  ): Map[String, Type] = {
    def symbolOf(tree: Tree): Option[String] = {
      val symbol = tree.symbol
      Option.when(symbol != Symbol.None) {
        uri.fold(symbol.value)(SemanticdbIndex.qualify(_, symbol.value))
      }
    }

    doc.tree
      .collect {
        case param: Term.Param =>
          (symbolOf(param.name), param.decltpe) match {
            case (Some(symbol), Some(tpe)) => List(symbol -> tpe)
            case _                         => Nil
          }
        case defn: Defn.Def =>
          (symbolOf(defn.name), defn.decltpe) match {
            case (Some(symbol), Some(tpe)) => List(symbol -> tpe)
            case _                         => Nil
          }
        case decl: Decl.Def =>
          symbolOf(decl.name).map(_ -> decl.decltpe).toList
        case defn @ Defn.Val(_, List(Pat.Var(name)), Some(tpe), _) =>
          symbolOf(name).map(_ -> tpe).toList
        case decl @ Decl.Val(_, List(Pat.Var(name)), tpe) =>
          symbolOf(name).map(_ -> tpe).toList
        case Pat.Typed(Pat.Var(name), tpe) =>
          symbolOf(name).map(_ -> tpe).toList
      }
      .flatten
      .toMap
  }

  /** This document's SemanticDB uri, matched by path suffix.
    *
    * Scalafix does not pass `--sourceroot` through to a rule, and SemanticDB
    * uris are relative to it, so the two are reconciled by finding the one
    * document whose uri the absolute file path ends with.
    */
  def currentUri(
      index: SemanticdbIndex,
      doc: SemanticDocument
  ): Option[String] = {
    val path = doc.input match {
      case scala.meta.Input.File(file, _)        => Some(file.toString)
      case scala.meta.Input.VirtualFile(name, _) => Some(name)
      case _                                     => None
    }
    path.flatMap { absolute =>
      val normalised = absolute.replace('\\', '/')
      index.documents
        .map(_.uri)
        // Matched in both directions. Under the CLI the document path is
        // absolute and the uri is a suffix of it; under testkit the input is a
        // VirtualFile whose name is *shorter* than the uri, so testing only one
        // direction silently disables every uri-gated patch.
        .filter(uri => normalised.endsWith(uri) || uri.endsWith(normalised))
        .maxByOption(_.length)
    }
  }

  /** The directory SemanticDB uris are relative to. */
  def inferSourceroot(index: SemanticdbIndex, classpath: List[Path]): Path = {
    val cwd = java.nio.file.Paths.get("").toAbsolutePath
    index.documents.iterator
      .map(_.uri)
      .flatMap { uri =>
        classpath.iterator.flatMap { root =>
          Iterator
            .iterate(Option(root.toAbsolutePath))(
              _.flatMap(p => Option(p.getParent))
            )
            .takeWhile(_.isDefined)
            .flatten
            .find(candidate =>
              java.nio.file.Files.exists(candidate.resolve(uri))
            )
        }
      }
      .nextOption()
      .getOrElse(cwd)
  }
}
