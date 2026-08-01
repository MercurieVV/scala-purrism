package fix

import java.nio.file.Files
import java.nio.file.Path

import scala.meta._
import scala.meta.internal.{semanticdb => s}

import fix.opaque.SemanticdbIndex

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
  *   - a def whose body calls another def that is also being re-shaped must be
  *     deferred, and the callee may live in a different file too.
  *
  * Both are keyed on the symbols SemanticDB resolved, not on spellings, so an
  * unrelated method that merely shares a name neither vetoes a lift nor gets
  * its own calls re-split. The AST is still parsed alongside the payload,
  * because candidacy depends on things only the source says -- default
  * arguments, varargs, the body's shape -- and because "is this occurrence a
  * call or a value?" is a question about syntax that SemanticDB records the
  * position of but not the answer to.
  */
final class KleisliLiftScope(val shapes: Map[String, LiftShape]) {
  def shapeOf(name: String): Option[LiftShape] = shapes.get(name)
  def isEmpty: Boolean = shapes.isEmpty
}

object KleisliLiftScope {
  val empty: KleisliLiftScope = new KleisliLiftScope(Map.empty)

  /** The symbol-keyed scope: the sources SemanticDB recorded, resolved against
    * the sourceroot, with every decision made about *symbols* rather than
    * spellings.
    *
    * Matching by name cannot tell two `load` methods on different objects
    * apart, cannot see an overload, and cannot tell a shadowing local from the
    * def it hides -- so a name used as a value anywhere had to veto every def
    * spelled that way. SemanticDB resolves each occurrence to the symbol the
    * compiler bound it to, which turns all three guesses into facts: the veto
    * lands only on the def actually referenced, and a call is re-split only if
    * it resolves to a lifted symbol.
    */
  def build(root: Path, index: SemanticdbIndex): KleisliLiftScope = {
    val analysed = analyse(root, index)

    // Where the compiler put each definition, so a parsed `Defn.Def` can be
    // given the symbol its own occurrence carries.
    val definitionSymbols: Map[(String, Int, Int), String] =
      analysed.flatMap { case (document, _) =>
        document.occurrences.collect {
          case occurrence
              if occurrence.role.isDefinition && occurrence.range.isDefined =>
            val range = occurrence.range.get
            (document.uri, range.startLine, range.startCharacter) ->
              SemanticdbIndex.qualify(document.uri, occurrence.symbol)
        }
      }.toMap

    val candidates =
      analysed.flatMap { case (document, tree) =>
        TypelevelPurrism.liftCandidates(tree).flatMap { case (_, shape, defn) =>
          definitionSymbols
            .get(
              (document.uri, defn.name.pos.startLine, defn.name.pos.startColumn)
            )
            // A `local…` symbol belongs to a def no other file can name, so
            // it has nothing to gain from a project-wide decision.
            .filterNot(_.startsWith("local"))
            .filterNot(_.contains("#local"))
            .map(symbol => symbol -> Candidate(document.uri, tree, shape, defn))
        }
      }.toMap

    val referencedAsValue = valueReferences(analysed)

    val eligible = candidates.filterNot { case (symbol, candidate) =>
      referencedAsValue.contains(symbol) ||
      (candidate.shape.arity > 1 &&
        TypelevelPurrism.placeholderCallSites(candidate.tree, candidate.defn))
    }

    new KleisliLiftScope(settle(eligible, analysed).map { case (symbol, c) =>
      symbol -> c.shape
    })
  }

  private def analyse(
      root: Path,
      index: SemanticdbIndex
  ): List[(s.TextDocument, Tree)] =
    index.documents.toList.flatMap { document =>
      val path = root.resolve(document.uri)

      if (!Files.isRegularFile(path)) None
      else parseSource(path).map(tree => (document, tree))
    }

  /** Symbols the project hands over *unapplied* somewhere -- `progress` in
    * `acquire(root, n, progress)`, passed as a `String => F[Unit]`.
    *
    * Nothing so referenced can become a `Kleisli`: `Kleisli[F, String, Unit]`
    * does not conform to a function type, so lifting it compiles the definition
    * and breaks every such call site, possibly in another file.
    *
    * Exposed because `PreferArrow` lifts signatures too, and its version of the
    * decision was per-file, so it could not see the reference at all. The set
    * is keyed on resolved symbols, so an unrelated method that shares a
    * spelling is unaffected.
    */
  def valueReferences(root: Path, index: SemanticdbIndex): Set[String] =
    valueReferenceCache.computeIfAbsent(
      root.toAbsolutePath.normalize,
      _ => valueReferences(analyse(root, index))
    )

  private val valueReferenceCache =
    new java.util.concurrent.ConcurrentHashMap[Path, Set[String]]()

  /** Only occurrences that resolve to a given symbol count, so an unrelated
    * method sharing the name no longer vetoes it.
    */
  private def valueReferences(
      analysed: List[(s.TextDocument, Tree)]
  ): Set[String] =
    analysed.flatMap { case (document, tree) =>
      document.occurrences.collect {
        case occurrence
            if occurrence.role.isReference && occurrence.range.isDefined &&
              TypelevelPurrism.isValueReferenceAt(
                tree,
                occurrence.range.get.startLine,
                occurrence.range.get.startCharacter
              ) =>
          SemanticdbIndex.qualify(document.uri, occurrence.symbol)
      }
    }.toSet

  private final case class Candidate(
      uri: String,
      tree: Tree,
      shape: LiftShape,
      defn: Defn.Def
  )

  /** A def whose body calls another def that is also being re-shaped is
    * deferred, because its replacement text carries a copy of that call.
    * Dropping one def can free another that only called it, so this runs to a
    * fixpoint. Membership is decided by the symbols SemanticDB recorded inside
    * the def's own source range.
    */
  private def settle(
      candidates: Map[String, Candidate],
      analysed: List[(s.TextDocument, Tree)]
  ): Map[String, Candidate] = {
    val referencesInside: Map[String, Set[String]] =
      candidates.map { case (symbol, candidate) =>
        val document =
          analysed.collectFirst {
            case (doc, _) if doc.uri == candidate.uri => doc
          }
        val body = candidate.defn.body.pos

        symbol -> document.toSet[s.TextDocument].flatMap { doc =>
          doc.occurrences.collect {
            case occurrence
                if occurrence.role.isReference && occurrence.range.exists {
                  range =>
                    range.startLine >= body.startLine &&
                    range.endLine <= body.endLine
                } =>
              SemanticdbIndex.qualify(doc.uri, occurrence.symbol)
          }.toSet
        }
      }

    def loop(current: Map[String, Candidate]): Map[String, Candidate] = {
      val live = current.keySet
      val next = current.filterNot { case (symbol, _) =>
        referencesInside
          .getOrElse(symbol, Set.empty)
          .exists(referenced =>
            referenced != symbol && live.contains(referenced)
          )
      }

      if (next.size == current.size) next else loop(next)
    }

    loop(candidates)
  }

  private def parseSource(path: Path): Option[Tree] = {
    val input = Input.VirtualFile(
      path.toString,
      new String(Files.readAllBytes(path), "UTF-8")
    )

    dialects.Scala3(input).parse[Source].toOption
  }
}
