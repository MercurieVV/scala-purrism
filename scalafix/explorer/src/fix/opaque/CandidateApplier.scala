package fix.opaque

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

import scala.util.Try
import scala.util.control.NonFatal

import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixError

import scala.jdk.CollectionConverters._

/** Applies one [[OpaqueCandidate]] as its own nested `PropagateOpaqueType`
  * invocation and writes the result straight to disk.
  *
  * Shared by [[ExploreOpaques]] (a standalone driver over a supervisor's own
  * classpath) and `PropagateOpaqueType`'s `autoDiscover.serialize` mode (a rule
  * invoking itself over the classpath it was configured with). Applying
  * candidates one nested invocation at a time -- rather than folding every
  * discovered spec into a single `fix()` pass -- means two clusters that touch
  * the same file never have their patches merged and conflict; the second
  * cluster's invocation simply sees that file's payload as stale (its own
  * `PropagateOpaqueType.fix` already treats that as a no-op with a lint
  * warning) and requires a recompile-and-rerun to pick it up, matching the
  * caller's own explanation to the operator.
  */
object CandidateApplier {

  /** What happened to one spec. Failures are values, not exceptions: a spec
    * that cannot be applied must not take the remaining ones down with it.
    */
  final case class Outcome(
      name: String,
      errors: List[String],
      warnings: List[String],
      crash: Option[String]
  ) {
    def ok: Boolean = crash.isEmpty && errors.isEmpty
  }

  /** Marker of the rule's "your payload predates this file" diagnostic. */
  private val StaleMarker = "is out of date"

  def applyOne(
      candidate: OpaqueCandidate,
      classpath: List[Path],
      sourceroot: Path,
      paths: List[Path]
  ): Outcome = {
    val confFile = Files.createTempFile(s"opaque-${candidate.name}-", ".conf")
    Files.write(
      confFile,
      OpaqueCandidateExplorer
        .renderHocon(List(candidate))
        .getBytes(StandardCharsets.UTF_8)
    )

    val warnings = List.newBuilder[String]

    try {
      val api = Scalafix.classloadInstance(getClass.getClassLoader)
      val arguments = api
        .newArguments()
        .withRules(List("PropagateOpaqueType").asJava)
        .withPaths(paths.asJava)
        .withClasspath(classpath.asJava)
        .withSourceroot(sourceroot)
        .withConfig(Optional.of(confFile))
        .withScalaVersion(scala.util.Properties.versionNumberString)
        // The rule lives on this module's own classloader, so no extra tool
        // classpath is fetched -- an empty child loader just delegates to it.
        .withToolClasspath(
          new java.net.URLClassLoader(Array.empty, getClass.getClassLoader)
        )

      // Merge points are the rule's request for a human decision, so they are
      // surfaced rather than counted as failures.
      arguments.evaluate().getFileEvaluations.foreach { evaluation =>
        evaluation.getDiagnostics.foreach { diagnostic =>
          warnings += s"${diagnostic.position().map[String](_.formatMessage("", diagnostic.message())).orElse(diagnostic.message())}"
        }
      }

      val errors: List[ScalafixError] = arguments.run().toList
      Outcome(candidate.name, errors.map(_.toString), warnings.result(), None)
    } catch {
      case NonFatal(error) =>
        Outcome(
          candidate.name,
          Nil,
          warnings.result(),
          Some(s"${error.getClass.getSimpleName}: ${error.getMessage}")
        )
    } finally Try(Files.deleteIfExists(confFile))
  }

  def report(outcomes: List[Outcome]): Unit = {
    outcomes.foreach { outcome =>
      val status = if (outcome.ok) "ok    " else "FAILED"
      println(s"$status ${outcome.name}")
      outcome.crash.foreach(message => println(s"       crash: $message"))
      outcome.errors.foreach(message => println(s"       error: $message"))

      val (stale, merges) =
        outcome.warnings.distinct.partition(_.contains(StaleMarker))
      merges.take(20).foreach(message => println(s"       warn : $message"))
      if (stale.nonEmpty)
        println(
          s"       skip : ${stale.size} file(s) already rewritten by an " +
            "earlier spec in this run, so their SemanticDB no longer matches"
        )
    }

    val failed = outcomes.filterNot(_.ok)
    println(
      s"\n${outcomes.size - failed.size}/${outcomes.size} spec(s) applied" +
        (if (failed.isEmpty) ""
         else s"; failed: ${failed.map(_.name).mkString(", ")}")
    )
  }

  /** The target's source files, taken from the payload so the rule is only
    * pointed at files it actually has SemanticDB for.
    */
  def sourceFiles(index: SemanticdbIndex, sourceroot: Path): List[Path] =
    index.documents
      .map(document => sourceroot.resolve(document.uri))
      .filter(Files.isRegularFile(_))
      .distinct
      .sortBy(_.toString)
}
