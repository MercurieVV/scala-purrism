package fix.opaque

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import fix.PropagateOpaqueType

/** Runs the candidate explorer over an already-compiled codebase and applies
  * `PropagateOpaqueType` to the clusters it ranks highest.
  *
  * Usage:
  * {{{
  * mill scalafix.explorer.runMain fix.opaque.ExploreOpaques \
  *   --target /path/to/gh-tasks-llm-executor \
  *   [--out <file>] [-n 10] [--basic-types scala/Int#,scala/Predef.String#] \
  *   [--dry-run]
  * }}}
  *
  * Defaults: `-n 10`, the basic types in `ExplorerConfig.DefaultBasicTypes`
  * (String, Int, Long, Double, Boolean, java.util.UUID), and `--out` next to
  * the target as `opaque-candidates.conf`.
  *
  * The target's sources are rewritten in place and nothing else: this never
  * touches git. Reviewing and reverting the result is the operator's job, and
  * the run says so on the way out.
  */
object ExploreOpaques {

  private final case class Options(
      target: Path,
      out: Path,
      topN: Int,
      basicTypes: List[String],
      dryRun: Boolean
  )

  def main(args: Array[String]): Unit =
    parse(args.toList) match {
      case Left(message) =>
        System.err.println(message)
        sys.exit(2)
      case Right(options) => run(options)
    }

  private def run(options: Options): Unit = {
    val roots = semanticdbRoots(options.target)
    if (roots.isEmpty)
      abort(
        s"no META-INF/semanticdb directory under ${options.target}. " +
          "Compile the target with SemanticDB on (-Ysemanticdb) and re-run; " +
          "without a payload the explorer cannot see any values at all."
      )

    val index = SemanticdbIndex.load(roots)
    if (index.documents.isEmpty)
      abort(
        s"SemanticDB directories found under ${options.target} but they hold " +
          "no documents. Recompile the target."
      )

    val sourceroot = PropagateOpaqueType.inferSourceroot(index, roots)
    println(s"target      : ${options.target}")
    println(s"sourceroot  : $sourceroot")
    println(s"semanticdb  : ${roots.mkString(", ")}")
    println(s"documents   : ${index.documents.size}")

    val facts =
      GraphBuilder.facts(index, new GraphBuilder(index, sourceroot).build())
    val config = ExplorerConfig(
      basicTypes = options.basicTypes,
      topN = options.topN
    )

    val candidates =
      OpaqueCandidateExplorer.withPlacement(
        index,
        OpaqueCandidateExplorer.explore(index, facts, config)
      )

    if (candidates.isEmpty)
      abort(
        "no candidate clusters found. Either the basic-type set excludes " +
          "everything in this codebase, or its values never flow between " +
          "definitions."
      )

    val hocon = OpaqueCandidateExplorer.renderHocon(candidates)
    println()
    println(OpaqueCandidateExplorer.renderRanking(candidates))
    println()
    println(hocon)

    Files.createDirectories(options.out.toAbsolutePath.getParent)
    Files.write(options.out, hocon.getBytes(StandardCharsets.UTF_8))
    println(s"wrote ${options.out}")

    if (options.dryRun)
      println("\n--dry-run: the rule was not applied.")
    else {
      val paths = CandidateApplier.sourceFiles(index, sourceroot)
      println(
        s"\napplying ${candidates.size} spec(s) over ${paths.size} file(s)\n"
      )
      val outcomes =
        candidates.map(CandidateApplier.applyOne(_, roots, sourceroot, paths))
      CandidateApplier.report(outcomes)
      println(
        "A spec that rewrites a file invalidates that file's SemanticDB, and " +
          "the rule refuses to patch against a stale payload. Expect roughly " +
          "the first spec per file to land; recompile the target and re-run to " +
          "apply the next one."
      )
      println(
        "Rewrites landed in the target working tree and were NOT committed -- " +
          "that is by design. Review with `git diff` in the target repo, and " +
          "`git checkout -- .` there to discard."
      )
    }
  }

  /** Every directory holding a SemanticDB payload, i.e. the parent of a
    * `META-INF/semanticdb`. These are exactly what the CLI would pass as
    * `--semanticdb-targetroots`.
    */
  private def semanticdbRoots(target: Path): List[Path] = {
    val stream = Files.walk(target)
    try
      stream
        .iterator()
        .asScala
        .filter(path => path.endsWith(Paths.get("META-INF/semanticdb")))
        .filter(Files.isDirectory(_))
        // Bloop keeps its own copy of every payload under
        // `.bloop/*/bloop-internal-classes`, often several generations of it.
        // Loading those alongside the real output directory means the index is
        // populated from whichever stale copy happens to sort first.
        .filterNot(_.toString.contains("/.bloop/"))
        .map(_.getParent.getParent)
        .toList
        .distinct
        .sortBy(_.toString)
    finally stream.close()
  }

  private def parse(args: List[String]): Either[String, Options] = {
    def loop(
        remaining: List[String],
        target: Option[Path],
        out: Option[Path],
        topN: Int,
        basicTypes: List[String],
        dryRun: Boolean
    ): Either[String, Options] =
      remaining match {
        case Nil =>
          target.toRight(usage).map { resolved =>
            Options(
              target = resolved,
              out = out.getOrElse(resolved.resolve("opaque-candidates.conf")),
              topN = topN,
              basicTypes = basicTypes,
              dryRun = dryRun
            )
          }
        case "--target" :: value :: rest =>
          loop(
            rest,
            Some(Paths.get(value).toAbsolutePath.normalize),
            out,
            topN,
            basicTypes,
            dryRun
          )
        case "--out" :: value :: rest =>
          loop(
            remaining = rest,
            target,
            Some(Paths.get(value).toAbsolutePath.normalize),
            topN,
            basicTypes,
            dryRun
          )
        case ("-n" | "--top") :: value :: rest =>
          value.toIntOption match {
            case Some(parsed) if parsed > 0 =>
              loop(rest, target, out, parsed, basicTypes, dryRun)
            case _ => Left(s"-n expects a positive integer, got '$value'")
          }
        case "--basic-types" :: value :: rest =>
          val types = value.split(',').toList.map(_.trim).filter(_.nonEmpty)
          if (types.isEmpty)
            Left("--basic-types expects a comma-separated list")
          else loop(rest, target, out, topN, types, dryRun)
        case "--dry-run" :: rest =>
          loop(rest, target, out, topN, basicTypes, true)
        case ("-h" | "--help") :: _ => Left(usage)
        case unknown :: _ => Left(s"unknown argument '$unknown'\n\n$usage")
      }

    loop(
      args,
      None,
      None,
      ExplorerConfig.DefaultTopN,
      ExplorerConfig.DefaultBasicTypes,
      false
    )
  }

  private def usage: String =
    s"""ExploreOpaques --target <path> [options]
       |
       |  --target <path>       compiled codebase to explore (required)
       |  --out <file>          where to write the HOCON fragment
       |                        (default: <target>/opaque-candidates.conf)
       |  -n, --top <count>     how many clusters to emit (default: ${ExplorerConfig.DefaultTopN})
       |  --basic-types <list>  comma-separated underlying type symbols
       |                        (default: ${ExplorerConfig.DefaultBasicTypes
        .mkString(",")})
       |  --dry-run             rank and write the config, but do not rewrite
       |
       |The target is rewritten in place; no git command is ever run against it.
       |""".stripMargin

  private def abort(message: String): Nothing = {
    System.err.println(message)
    sys.exit(1)
  }
}
