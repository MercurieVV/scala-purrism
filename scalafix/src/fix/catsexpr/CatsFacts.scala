package fix.catsexpr

import scala.meta._

import scalafix.v1.MethodSignature
import scalafix.v1.SemanticDocument
import scalafix.v1.TypeRef
import scalafix.v1.ValueSignature
import scalafix.v1.XtensionTreeScalafix

import fix.SemanticSupport

/** The symbol questions the Cats expression rules ask.
  *
  * Kept behind a plain interface so the rewrite logic can be driven by a fake
  * with no compiler in the loop, the way `Closure` sits behind `Facts`
  * (`docs/RULES.md`).
  *
  * Every method answers "no" when it cannot resolve a symbol. A rewrite that
  * does not fire costs nothing; one that fires on the wrong receiver produces
  * code that does not compile.
  */
trait CatsFacts {

  /** For a `Applicative[F]`-shaped receiver, the resolved symbol of the
    * typeclass companion, e.g. `"cats/Applicative."`.
    */
  def typeclassObject(term: Term): Option[String]

  /** Whether a `receiver.name(...)` call site resolves to a Cats typeclass
    * member or to Cats syntax ops.
    */
  def isCatsOperation(select: Term.Select): Boolean

  /** Whether `term` resolves to one of `symbols`, which are full SemanticDB
    * symbols such as `"scala/util/Right."`.
    */
  def resolvesTo(term: Term, symbols: Set[String]): Boolean

  /** Whether `term` is an effect of `Unit`, i.e. its type is `F[Unit]` for some
    * `F`.
    *
    * `whenA` and `unlessA` return `F[Unit]`, so rewriting `if (c) fa else
    * F.unit` to `fa.whenA(c)` only preserves the type when `fa` is already
    * `F[Unit]`. Answers false whenever the type is not plainly resolvable,
    * which costs a rewrite rather than a compile.
    */
  def isUnitEffect(term: Term): Boolean
}

object CatsFacts {

  /** Cats owns every symbol under the `cats/` root, so a call site backed by
    * `cats/Functor.Ops#map().`, `cats/syntax/ApplicativeIdOps#pure().` or
    * `cats/Applicative#pure().` is a Cats operation, while `List`'s own
    * `scala/collection/immutable/List#map().` is not.
    */
  private val CatsSymbolPrefix = "cats/"

  private val UnitTypeSymbol = "scala/Unit#"

  def semantic(implicit doc: SemanticDocument): CatsFacts =
    new SemanticCatsFacts

  /** Test-only: decides by identifier spelling, which is exactly what the rules
    * must never do. Exists so unit tests can exercise how a rewrite is
    * rendered, separately from whether it is allowed to fire.
    */
  def bySpelling(
      typeclasses: Map[String, String] = defaultTypeclassSpellings,
      catsReceivers: Option[Set[String]] = None,
      constructors: Map[String, String] = defaultConstructorSpellings,
      unitEffects: Set[String] = Set.empty
  ): CatsFacts = new CatsFacts {
    def typeclassObject(term: Term): Option[String] =
      spelling(term).flatMap(typeclasses.get)

    def isCatsOperation(select: Term.Select): Boolean =
      catsReceivers.forall(_.contains(select.qual.syntax))

    def resolvesTo(term: Term, symbols: Set[String]): Boolean =
      spelling(term).flatMap(constructors.get).exists(symbols)

    def isUnitEffect(term: Term): Boolean = unitEffects(term.syntax)

    private def spelling(term: Term): Option[String] =
      term match {
        case Term.Name(name)                    => Some(name)
        case Term.Select(_, Term.Name(name))    => Some(name)
        case Term.ApplyType.After_4_6_0(fun, _) => spelling(fun)
        case _                                  => None
      }
  }

  private val defaultTypeclassSpellings: Map[String, String] = Map(
    "Functor" -> "cats/Functor.",
    "Applicative" -> "cats/Applicative.",
    "ApplicativeError" -> "cats/ApplicativeError.",
    "FlatMap" -> "cats/FlatMap.",
    "Monad" -> "cats/Monad.",
    "MonadError" -> "cats/MonadError.",
    "MonadThrow" -> "cats/package.MonadThrow.",
    "ApplicativeThrow" -> "cats/package.ApplicativeThrow.",
    "Sync" -> "cats/effect/Sync.",
    "Async" -> "cats/effect/Async.",
    "IO" -> "cats/effect/IO."
  )

  private val defaultConstructorSpellings: Map[String, String] = Map(
    "Some" -> "scala/Some.",
    "None" -> "scala/None.",
    "Right" -> "scala/util/Right.",
    "Left" -> "scala/util/Left."
  )

  private final class SemanticCatsFacts(implicit doc: SemanticDocument)
      extends CatsFacts {

    private lazy val synthetics = SemanticSupport.syntheticEvidence

    def typeclassObject(term: Term): Option[String] =
      term match {
        case name: Term.Name                    => resolved(name)
        case select: Term.Select                => resolved(select.name)
        case Term.ApplyType.After_4_6_0(fun, _) => typeclassObject(fun)
        case _                                  => None
      }

    def isCatsOperation(select: Term.Select): Boolean =
      SemanticSupport
        .symbolsAt(select.pos, select.name.symbol, synthetics)
        .exists(_.value.startsWith(CatsSymbolPrefix))

    def resolvesTo(term: Term, symbols: Set[String]): Boolean =
      constructorSymbol(term).exists { symbol =>
        // `Some(x)` can resolve to the companion `scala/Some.` or straight to
        // `scala/Some.apply().`; both identify the same constructor.
        symbols.exists(expected =>
          symbol == expected || symbol.startsWith(expected)
        )
      }

    def isUnitEffect(term: Term): Boolean =
      effectElementType(term).contains(UnitTypeSymbol)

    /** The `A` of a `F[A]`-typed term, when the term is a plain reference whose
      * signature SemanticDB carries.
      */
    private def effectElementType(term: Term): Option[String] =
      term.symbol.info.flatMap { info =>
        val tpe = info.signature match {
          case ValueSignature(value)           => Some(value)
          case MethodSignature(_, _, returned) => Some(returned)
          case _                               => None
        }
        tpe.collect { case TypeRef(_, _, List(TypeRef(_, element, _))) =>
          element.value
        }
      }

    private def constructorSymbol(term: Term): Option[String] =
      term match {
        case name: Term.Name                    => resolved(name)
        case select: Term.Select                => resolved(select.name)
        case Term.ApplyType.After_4_6_0(fun, _) => constructorSymbol(fun)
        case _                                  => None
      }

    private def resolved(name: Term.Name): Option[String] = {
      val symbol = name.symbol
      if (symbol.isNone || symbol.isLocal) None else Some(symbol.value)
    }
  }

  /** Symbols the rules accept for each Cats typeclass entry point. Replaces the
    * name tables these rules used to dispatch on: `Monad` is a spelling that
    * any local object can claim, `cats/Monad.` is not.
    */
  object Typeclasses {
    private def group(names: String*): Set[String] = names.toSet

    /** `MonadThrow` and `ApplicativeThrow` are aliases declared in the `cats`
      * package object, so the compiler emits `cats/package.MonadThrow.` where
      * the class-defined typeclasses get `cats/Monad.`. Both spellings name the
      * same entry point.
      */
    private val monadThrow = "cats/package.MonadThrow."
    private val applicativeThrow = "cats/package.ApplicativeThrow."

    val pure: Set[String] = group(
      "cats/Applicative.",
      "cats/Monad.",
      monadThrow,
      applicativeThrow,
      "cats/effect/Sync.",
      "cats/effect/Async.",
      "cats/effect/IO."
    )

    val raiseError: Set[String] = group(
      "cats/ApplicativeError.",
      "cats/MonadError.",
      monadThrow,
      applicativeThrow,
      "cats/effect/Sync.",
      "cats/effect/Async.",
      "cats/effect/IO."
    )

    val map: Set[String] = group(
      "cats/Functor.",
      "cats/Applicative.",
      "cats/Monad.",
      "cats/FlatMap.",
      monadThrow
    )

    val flatMap: Set[String] = group(
      "cats/FlatMap.",
      "cats/Monad.",
      monadThrow,
      "cats/effect/Sync.",
      "cats/effect/Async.",
      "cats/effect/IO."
    )
  }

  /** Stdlib constructors the simplifications recognise, by symbol rather than
    * by the names `Some` / `None` / `Right` / `Left`, which any local binding
    * can shadow.
    */
  object Constructors {
    val some: Set[String] = SemanticSupport.stdlibConstructorSymbols("Some")
    val none: Set[String] = SemanticSupport.stdlibConstructorSymbols("None")
    val right: Set[String] = SemanticSupport.stdlibConstructorSymbols("Right")
    val left: Set[String] = SemanticSupport.stdlibConstructorSymbols("Left")
  }
}
