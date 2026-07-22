package fix

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters._
import scala.meta._

/** How a lifted def's argument list splits: which of its value parameters
  * become the Kleisli's (tupled) input and which stay a leading parameter list.
  * `callbacks` is positional over the original parameters.
  */
final case class LiftShape(callbacks: List[Boolean]) {
  def arity: Int = callbacks.length
  def hasCallbacks: Boolean = callbacks.contains(true)
}

/** The set of defs that may be lifted to `Kleisli`, decided once for the whole
  * project rather than per file.
  *
  * Scalafix rewrites one document at a time and can only patch the document it
  * is given, so lifting a def in file A while its callers live in file B needs
  * *both* files to reach the same conclusion about A's def -- otherwise B keeps
  * passing the old argument list and the project stops compiling. A per-file
  * decision cannot guarantee that; a decision computed from the whole source
  * tree can, because every file computes the identical answer.
  *
  * Two project-wide facts are what a single file cannot see:
  *
  *   - a def referenced *unapplied* anywhere (`acquire(root, n, progress)`
  *     hands `progress` over as a `String => F[Unit]`) cannot become a Kleisli,
  *     since `Kleisli[F, String, Unit]` does not conform to a function type,
  *     and the reference may sit in a different file from the def;
  *   - a name defined more than once with different shapes cannot be re-split
  *     by name, so it is dropped rather than guessed at.
  *
  * The scan is syntactic on purpose. It runs before any rewrite, when the
  * project still compiles, and it needs no SemanticDB payload of its own --
  * matching call sites by name and arity is the same thing the in-file path has
  * always done, just applied across the source tree.
  */
final class KleisliLiftScope(val shapes: Map[String, LiftShape]) {
  def shapeOf(name: String): Option[LiftShape] = shapes.get(name)
  def isEmpty: Boolean = shapes.isEmpty
}

object KleisliLiftScope {
  val empty: KleisliLiftScope = new KleisliLiftScope(Map.empty)

  private val IgnoredDirectories =
    Set(".git", ".semanticdb", "out", "target", ".worktrees", ".bloop", ".bsp")

  def build(root: Path): KleisliLiftScope =
    build(sourceFiles(root))

  def build(sources: List[Path]): KleisliLiftScope =
    new KleisliLiftScope(
      TypelevelPurrism.liftScopeShapes(sources.flatMap(parseSource))
    )

  def sourceFiles(root: Path): List[Path] =
    if (!Files.isDirectory(root)) Nil
    else
      Files
        .walk(root)
        .iterator()
        .asScala
        .filter(path => path.toString.endsWith(".scala"))
        .filterNot(path =>
          Option(root.relativize(path).getParent).exists(
            _.iterator().asScala
              .exists(part => IgnoredDirectories.contains(part.toString))
          )
        )
        .toList

  private def parseSource(path: Path): Option[Tree] = {
    val input = Input.VirtualFile(
      path.toString,
      new String(Files.readAllBytes(path), "UTF-8")
    )

    dialects.Scala3(input).parse[Source].toOption
  }
}
