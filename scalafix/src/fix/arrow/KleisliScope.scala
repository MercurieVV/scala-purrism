package fix.arrow

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.{semanticdb => s}

import fix.opaque.SemanticdbIndex

/** What a declaration hands back, for symbols declared *outside* the file being
  * rewritten.
  *
  * @param returnsKleisli
  *   whether the declared result type resolves to `cats.data.Kleisli`, through
  *   any chain of aliases.
  * @param explicitClauses
  *   how many explicit parameter lists must be supplied before the result is
  *   reached. Implicit/given clauses are excluded, since they never appear in
  *   the syntax being counted.
  */
final case class KleisliDecl(returnsKleisli: Boolean, explicitClauses: Int)

/** Project-wide answers to "is this a Kleisli?" for cross-file callees.
  *
  * [[KleisliType]] normally asks scalafix's own symbol table (`Symbol#info`),
  * and for a symbol declared in the file being rewritten that works. For
  * anything declared one file over it does not, and the reason is structural
  * rather than a misconfiguration: scalafix resolves classpath symbols by
  * reading *classfiles*, and a Scala 3 classfile carries its signature as
  * TASTy, which the classfile-to-SemanticDB converter cannot decode. So `info`
  * returns nothing, the callee looks like a non-Kleisli, and the rewrite is
  * declined -- indistinguishable, from the outside, from a rule that correctly
  * found nothing.
  *
  * Measured on `gh-tasks-llm-executor`: an identical body rewrites when its
  * callee is in the same file and is declined when the callee moves to another
  * file. Passing scalafix `--classpath` in every combination of classes
  * directory and semanticdb targetroot did not change it.
  *
  * The fix is the one `PreferKleisli` already relies on: read the `.semanticdb`
  * payloads the compiler emitted, which do carry full Scala 3 signatures, and
  * answer from those. See `fix.KleisliLiftScope` for the same move.
  */
final class KleisliScope(private val decls: Map[String, KleisliDecl]) {

  def declOf(symbol: String): Option[KleisliDecl] = decls.get(symbol)

  def isEmpty: Boolean = decls.isEmpty
}

object KleisliScope {

  val empty: KleisliScope = new KleisliScope(Map.empty)

  private val KleisliSymbol = "cats/data/Kleisli#"

  /** Matches [[KleisliType]]'s own guard against a cyclic alias chain. */
  private val MaxDealiasDepth = 16

  /** The scope in force for the current run.
    *
    * A parameter would have to be threaded through every one of `ArrowParser`'s
    * recursive descent methods to reach the handful of [[KleisliType]] calls at
    * the leaves, so it is installed once instead. That is sound here for the
    * same reason `SemanticdbIndex`'s own cache is: the scope is derived purely
    * from the classpath, which is fixed for a run, and the rule instance that
    * installs it is constructed once and then handed every document.
    */
  private val installed = new AtomicReference[KleisliScope](empty)

  def install(scope: KleisliScope): Unit = installed.set(scope)

  def current: KleisliScope = installed.get()

  /** Every declaration in the analysed sources, keyed by its global symbol.
    *
    * Local symbols are dropped: they are numbered per document, so a global map
    * would silently merge unrelated values from different files -- and a local
    * is by definition in the file being rewritten, where `Symbol#info` already
    * answers.
    */
  def build(index: SemanticdbIndex): KleisliScope = {
    val infos = index.symbolInfo
    val decls = infos.iterator
      .collect {
        case (symbol, info) if !symbol.startsWith("local") =>
          symbol -> declOf(info, infos)
      }
      .collect { case (symbol, Some(decl)) => symbol -> decl }
    new KleisliScope(decls.toMap)
  }

  /** Keyed on the resolved classpath, like `SemanticdbIndex`'s own cache. The
    * umbrella `TypelevelPurrism` rule constructs a fresh `PreferArrow` per
    * document, so without this the whole-project scan would be folded again for
    * every file.
    */
  private val cache =
    new java.util.concurrent.ConcurrentHashMap[List[Path], KleisliScope]()

  def load(classpath: List[Path]): KleisliScope =
    if (classpath.isEmpty) empty
    else {
      val key = classpath.map(_.toAbsolutePath.normalize).distinct
      cache.computeIfAbsent(key, _ => build(SemanticdbIndex.load(classpath)))
    }

  private def declOf(
      info: s.SymbolInformation,
      infos: Map[String, s.SymbolInformation]
  ): Option[KleisliDecl] =
    info.signature match {
      case s.ValueSignature(tpe) =>
        Some(KleisliDecl(resolvesToKleisli(tpe, infos, 0), 0))
      case method: s.MethodSignature =>
        Some(
          KleisliDecl(
            resolvesToKleisli(method.returnType, infos, 0),
            explicitClauseCount(method, infos)
          )
        )
      case _ => None
    }

  private def explicitClauseCount(
      method: s.MethodSignature,
      infos: Map[String, s.SymbolInformation]
  ): Int =
    method.parameterLists.count { clause =>
      clause.symlinks.nonEmpty &&
      !clause.symlinks.forall(symbol => isImplicit(symbol, infos))
    }

  private def isImplicit(
      symbol: String,
      infos: Map[String, s.SymbolInformation]
  ): Boolean =
    infos.get(symbol).exists { info =>
      val implicitBit = s.SymbolInformation.Property.IMPLICIT.value
      val givenBit = s.SymbolInformation.Property.GIVEN.value
      (info.properties & (implicitBit | givenBit)) != 0
    }

  /** Mirrors `KleisliType.resolvesToKleisli`, over the payload's own type
    * representation rather than scalafix's.
    *
    * Two shapes matter beyond the plain `TypeRef`. A parameterless
    * `def flow: -->[F, A, B]` reaches the payload as a `ByNameType`, and a
    * higher-kinded alias body (`[A, B] =>> Kleisli[F, A, B]`) as a
    * `UniversalType`; missing either rejects the declaration silently.
    */
  private def resolvesToKleisli(
      tpe: s.Type,
      infos: Map[String, s.SymbolInformation],
      depth: Int
  ): Boolean =
    if (depth > MaxDealiasDepth) false
    else
      tpe match {
        case s.TypeRef(_, symbol, _) if symbol == KleisliSymbol => true
        case s.TypeRef(_, symbol, _) =>
          dealias(symbol, infos).exists(resolvesToKleisli(_, infos, depth + 1))
        case s.ByNameType(result) => resolvesToKleisli(result, infos, depth + 1)
        case s.UniversalType(_, result) =>
          resolvesToKleisli(result, infos, depth + 1)
        case s.AnnotatedType(_, result) =>
          resolvesToKleisli(result, infos, depth + 1)
        case _ => false
      }

  /** The right-hand side of a type alias, or `None` for an abstract type.
    *
    * The discriminator is the same one [[KleisliType.dealias]] uses and for the
    * same reason: an alias records its right-hand side as both bounds, an
    * abstract type parameter records genuine ones. Without it the corpus's
    * `ProgramArrows[-->[_, _]]` type parameter would be fused with the `-->`
    * Kleisli alias that shares its spelling.
    */
  private def dealias(
      symbol: String,
      infos: Map[String, s.SymbolInformation]
  ): Option[s.Type] =
    infos.get(symbol).map(_.signature).flatMap {
      case s.TypeSignature(_, lowerBound, upperBound)
          if lowerBound == upperBound && upperBound != s.Type.Empty =>
        Some(upperBound)
      case _ => None
    }
}
