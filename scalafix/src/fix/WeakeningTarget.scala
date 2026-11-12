package fix

import scala.meta._

import fix.hkt.CatsIndex
import scalafix.v1._

/** Which bound `TypeclassWeakening` writes in place of an over-strong one.
  *
  * The rule's gate decides *whether* `Monad` is enough; this decides how much
  * less suffices. The answer is the weakest of [[Targets]] that covers every
  * capability the owner's body needs, read from the compiler rather than from
  * method names:
  *
  *   - the Cats typeclass a called operation resolves to through the index
  *     (`fa.map` resolves via `Functor.Ops#map` to `Functor`), and
  *   - every call the compiler handed the effect's evidence to. SemanticDB
  *     records those as synthetics -- `toFunctorOps(fa)(using ev)`,
  *     `xs.traverse(f)(using ev)` -- and each must be explained: by an indexed
  *     capability, by a `toXOps` syntax conversion naming its typeclass, or by
  *     the typeclass evidence its signature declares.
  *
  * A call that cannot be explained makes the answer `Monad`, the bound the gate
  * already proved sufficient. `traverse` is the common case: it belongs to
  * `Traverse`, and the `Applicative[G]` it asks of the effect sits in a
  * signature that is not readable for a TASTy-only library, so whether
  * `Functor` would do is unknown and the answer stays `Monad`.
  *
  * Intermediate classes (`Apply`, `FlatMap`, `Semigroupal`) are deliberately
  * not targets: a body that needs one is lifted to the next target that extends
  * it. Only classes on `Monad`'s lineage count; one outside it (`Traverse`,
  * `Foldable`) constrains another type, not the effect.
  *
  * The answer is also `Monad` without a semantic document, when nothing
  * resolved, when a for-comprehension desugars into calls the tree does not
  * show, and when a requirement is stronger than `Monad` (a helper the gate let
  * through because it is itself weakenable).
  */
private[fix] object WeakeningTarget {
  val Targets: List[String] = List("Functor", "Applicative", "Monad")

  private val Fallback = "Monad"
  private val MonadSymbol = Symbol("cats/Monad#")
  private val SyntaxConversion = "to([A-Za-z]+)Ops".r

  private lazy val index: CatsIndex = CatsIndex.load()

  def select(
      owner: Tree,
      effectTypeName: String,
      semanticDocument: Option[SemanticDocument]
  ): String =
    semanticDocument
      .filterNot(_ => hasComprehension(owner))
      .flatMap(doc => required(owner, effectTypeName)(using doc))
      .flatMap(covering)
      .getOrElse(Fallback)

  private def hasComprehension(owner: Tree): Boolean =
    owner.collect {
      case _: Term.For      => ()
      case _: Term.ForYield => ()
    }.nonEmpty

  /** Every lineage typeclass the owner needs, or `None` when some call that
    * receives the effect's evidence cannot be explained.
    */
  private def required(owner: Tree, effectTypeName: String)(implicit
      doc: SemanticDocument
  ): Option[Set[Symbol]] = {
    val direct =
      owner.collect { case name: Term.Name =>
        resolvedOwner(name.symbol).toList
      }.flatten

    val synthetics =
      doc.synthetics.toList.filter(synthetic => anchoredIn(synthetic, owner))
    val conversions = conversionsByPosition(synthetics)

    val explained =
      synthetics
        .flatMap(evidenceCalls(_, effectTypeName))
        .map { case (function, original) =>
          explain(
            original
              .filterNot(_ => isMethod(function))
              .flatMap(position => conversions.get(position))
              .getOrElse(function)
          )
        }

    Option
      .when(explained.forall(_.nonEmpty))(direct ++ explained.flatten)
      .map(_.filter(onMonadLineage).toSet)
  }

  private def resolvedOwner(symbol: Symbol): Option[Symbol] =
    index
      .resolveSyntax(symbol)
      .map(_.owner)
      .orElse(index.primitiveOwner(symbol))
      .flatMap(typeclassOf)

  private def explain(function: Symbol)(implicit
      doc: SemanticDocument
  ): List[Symbol] =
    (resolvedOwner(function).toList ++ conversion(function).toList ++
      signatureEvidence(function)).filter(onMonadLineage)

  private def conversion(function: Symbol): Option[Symbol] =
    function.displayName match {
      case SyntaxConversion(name) =>
        Some(Symbol(s"cats/$name#")).filter(index.typeclasses.contains)
      case _ => None
    }

  private def signatureEvidence(function: Symbol)(implicit
      doc: SemanticDocument
  ): List[Symbol] =
    function.info.toList.flatMap(_.signature match {
      case MethodSignature(_, parameterLists, _) =>
        parameterLists.flatten.flatMap(_.signature match {
          case ValueSignature(TypeRef(_, typeclass, List(_)))
              if index.typeclasses.contains(typeclass) =>
            List(typeclass)
          case _ => Nil
        })
      case _ => Nil
    })

  /** A method's own `using` clause, as opposed to the evidence of a conversion
    * applied to a value: `xs.traverse(f)(using ev)` belongs to `traverse` even
    * when `toFunctorOps(xs.traverse(f))` sits on the same span.
    */
  private def isMethod(symbol: Symbol): Boolean =
    symbol.value.endsWith(").")

  /** Implicit conversions applied to source expressions, keyed by the span of
    * the converted expression.
    *
    * Scala 3 records a syntax conversion and the evidence it takes as two
    * synthetics on the same span -- `toFunctorOps(fa)` and `fa(using ev)` -- so
    * the evidence application names `fa`, not the conversion that wanted the
    * evidence.
    */
  private def conversionsByPosition(synthetics: List[SemanticTree])(implicit
      doc: SemanticDocument
  ): Map[(Int, Int), Symbol] =
    synthetics.flatMap {
      case ApplyTree(function, List(OriginalTree(original))) =>
        List((original.pos.start, original.pos.end) -> functionSymbol(function))
      case _ => Nil
    }.toMap

  /** The function of every synthetic application handed the effect's evidence
    * -- `Symbol.None` when it cannot be named -- with the span of the source
    * expression when that function is one.
    */
  private def evidenceCalls(
      tree: SemanticTree,
      effectTypeName: String
  )(implicit doc: SemanticDocument): List[(Symbol, Option[(Int, Int)])] =
    tree match {
      case ApplyTree(function, arguments) =>
        val here =
          if (arguments.exists(passesEvidence(_, effectTypeName)))
            List(functionSymbol(function) -> originalSpan(function))
          else Nil
        here ++ (function :: arguments).flatMap(
          evidenceCalls(_, effectTypeName)
        )
      case SelectTree(qualifier, _) => evidenceCalls(qualifier, effectTypeName)
      case TypeApplyTree(function, _) =>
        evidenceCalls(function, effectTypeName)
      case FunctionTree(_, body) => evidenceCalls(body, effectTypeName)
      case MacroExpansionTree(before, _) =>
        evidenceCalls(before, effectTypeName)
      case _ => Nil
    }

  private def passesEvidence(
      tree: SemanticTree,
      effectTypeName: String
  ): Boolean =
    tree match {
      case IdTree(info) => isEvidence(info, effectTypeName)
      case ApplyTree(function, arguments) =>
        (function :: arguments).exists(passesEvidence(_, effectTypeName))
      case SelectTree(qualifier, id) =>
        passesEvidence(qualifier, effectTypeName) ||
        passesEvidence(id, effectTypeName)
      case TypeApplyTree(function, _) =>
        passesEvidence(function, effectTypeName)
      case _ => false
    }

  /** A value of shape `TC[F]` for the effect `F` -- the context-bound evidence
    * or anything derived from it.
    */
  private def isEvidence(info: SymbolInformation, effectTypeName: String) =
    info.signature match {
      case ValueSignature(TypeRef(_, _, List(TypeRef(_, effect, Nil)))) =>
        effect.displayName == effectTypeName
      case _ => false
    }

  private def functionSymbol(tree: SemanticTree)(implicit
      doc: SemanticDocument
  ): Symbol =
    tree match {
      case IdTree(info)                => info.symbol
      case SelectTree(_, IdTree(info)) => info.symbol
      case TypeApplyTree(function, _)  => functionSymbol(function)
      case ApplyTree(function, _)      => functionSymbol(function)
      case OriginalTree(term)          => termSymbol(term)
      case OriginalSubTree(term)       => termSymbol(term)
      case _                           => Symbol.None
    }

  private def originalSpan(tree: SemanticTree): Option[(Int, Int)] =
    tree match {
      case OriginalTree(original) =>
        Some((original.pos.start, original.pos.end))
      case OriginalSubTree(original) =>
        Some((original.pos.start, original.pos.end))
      case _ => None
    }

  private def termSymbol(tree: Tree)(implicit doc: SemanticDocument): Symbol =
    tree match {
      case apply: Term.Apply         => termSymbol(apply.fun)
      case applyType: Term.ApplyType => termSymbol(applyType.fun)
      case select: Term.Select       => select.name.symbol
      case other                     => other.symbol
    }

  private def anchoredIn(synthetic: SemanticTree, owner: Tree): Boolean =
    anchor(synthetic).exists(position =>
      owner.pos.start <= position.start && position.end <= owner.pos.end
    )

  private def anchor(tree: SemanticTree): Option[Position] =
    tree match {
      case OriginalTree(original)    => Some(original.pos)
      case OriginalSubTree(original) => Some(original.pos)
      case ApplyTree(function, arguments) =>
        (function :: arguments).iterator.flatMap(anchor).nextOption()
      case SelectTree(qualifier, _)      => anchor(qualifier)
      case TypeApplyTree(function, _)    => anchor(function)
      case FunctionTree(_, body)         => anchor(body)
      case MacroExpansionTree(before, _) => anchor(before)
      case _                             => None
    }

  private def typeclassOf(method: Symbol): Option[Symbol] = {
    val value = method.value
    val separator = value.indexOf('#')
    Option
      .when(separator > 0)(Symbol(value.substring(0, separator + 1)))
      .filter(index.typeclasses.contains)
  }

  private def onMonadLineage(typeclass: Symbol): Boolean =
    typeclass == MonadSymbol ||
      index.isAncestor(typeclass, MonadSymbol) ||
      index.isAncestor(MonadSymbol, typeclass)

  private def covering(required: Set[Symbol]): Option[String] =
    Option
      .when(required.nonEmpty)(required)
      .flatMap(needed =>
        Targets.find { target =>
          val symbol = Symbol(s"cats/$target#")
          needed.forall(r => r == symbol || index.isAncestor(r, symbol))
        }
      )
}
