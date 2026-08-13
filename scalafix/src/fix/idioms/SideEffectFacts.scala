package fix.idioms

import scala.meta._

import scalafix.v1.SemanticDocument
import scalafix.v1.XtensionTreeScalafix

/** Which call sites are effects the type does not mention.
  *
  * Behind an interface for the reason `docs/RULES.md` gives: the decision of
  * *what counts as an effect* has to be answerable by a fake, so the reporting
  * and the rendering can be tested without a compiler in the loop.
  *
  * Every answer is by symbol. `println` is a spelling any local method can
  * claim; `scala/Predef.println().` is not.
  */
private[fix] trait SideEffectFacts {

  /** Every symbol a term resolves to, including through synthetics. */
  def symbolsAt(term: Term): List[String]
}

private[fix] object SideEffectFacts {

  /** The term's own resolved symbol, and nothing else.
    *
    * Deliberately *not* `SemanticSupport.symbolsAt`, which folds in every
    * synthetic overlapping the term's position. That is right for Cats syntax,
    * where the operation only exists as an implicit conversion — but here it
    * over-matches: a large term's position overlaps every synthetic inside it,
    * so an enclosing expression inherits a nested `java.io` call's symbol and
    * reports a method that performs no effect of its own. What this rule looks
    * for is a plain call to a plain method, which the base symbol already
    * names.
    */
  def semantic(implicit doc: SemanticDocument): SideEffectFacts =
    new SideEffectFacts {
      def symbolsAt(term: Term): List[String] = {
        val symbol = baseSymbol(term)
        if (symbol.isNone || symbol.isLocal) Nil else List(symbol.value)
      }

      private def baseSymbol(term: Term): scalafix.v1.Symbol =
        term match {
          case select: Term.Select       => select.name.symbol
          case name: Term.Name           => name.symbol
          case apply: Term.Apply         => baseSymbol(apply.fun)
          case applyType: Term.ApplyType => baseSymbol(applyType.fun)
          case other                     => other.symbol
        }
    }

  /** Test-only: decides by spelling, which the rule must never do. Exists so
    * the report and its position can be exercised without a compiler.
    */
  def bySpelling(effects: Map[String, String]): SideEffectFacts =
    new SideEffectFacts {
      def symbolsAt(term: Term): List[String] =
        spelling(term).flatMap(effects.get).toList

      private def spelling(term: Term): Option[String] =
        term match {
          case Term.Name(name)                 => Some(name)
          case Term.Select(_, Term.Name(name)) => Some(name)
          case apply: Term.Apply               => spelling(apply.fun)
          case _                               => None
        }
    }

  /** Calls that do something to the world, by symbol prefix.
    *
    * A prefix rather than an exact symbol because the overloads are the point:
    * `println`, `println(+1)`, every member of `Files`, every constructor under
    * `java/io` -- all of them touch the world, and enumerating them would be a
    * list that goes stale the first time someone uses a different one.
    */
  val UnsuspendedPrefixes: List[String] = List(
    "scala/Predef.println",
    "scala/Predef.print",
    "scala/Console.",
    "scala/util/Random",
    "scala/io/StdIn",
    "java/lang/System#",
    "java/lang/Thread#",
    "java/lang/Runtime#",
    "java/nio/file/Files#",
    "java/io/",
    "java/net/",
    "cats/effect/IO#unsafeRun",
    "cats/effect/SyncIO#unsafeRun",
    "cats/effect/unsafe/"
  )

  def isUnsuspended(symbol: String): Boolean =
    UnsuspendedPrefixes.exists(symbol.startsWith)
}
