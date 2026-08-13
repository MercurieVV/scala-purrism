package fix.idioms

import scala.meta._

import fix.catsexpr.CatsFacts

/** Loops that index a collection they already hold, and folds that thread an
  * effect by hand.
  *
  * `xs.indices.map(i => f(xs(i)))` reaches for the element through the index it
  * just produced. `zipWithIndex` hands over both, and where the index turns out
  * to be unused -- which is most of the time -- the whole index disappears.
  */
private[fix] object IndexedMapRules {

  import TermShapes._

  def rewrites(tree: Tree, facts: CatsFacts): List[IdiomRewrite] =
    tree.collect { case term: Term =>
      zipWithIndex(term).orElse(foldM(term, facts))
    }.flatten

  /** `xs.indices.map(i => body)` ->
    * `xs.zipWithIndex.map { case (x, i) => ... }`, or `xs.map(x => ...)` when
    * the index was only ever a way back to the element.
    */
  private def zipWithIndex(term: Term): Option[IdiomRewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(IndexRange(collection), Term.Name(method))
              if IterationMethods.contains(method) =>
            for {
              function <- singleArg(apply.argClause.values).collect {
                case function: Term.Function => function
              }
              param <- singleParam(function)
              index <- namedParam(param)
              (body, element, indexUsed) <- substituteElement(
                function.body,
                collection,
                index
              )
            } yield IdiomRewrite(
              term,
              if (indexUsed)
                s"${collection.syntax}.zipWithIndex.$method { case ($element, $index) => $body }"
              else
                s"${collection.syntax}.$method($element => $body)"
            )
          case _ =>
            None
        }
      case _ =>
        None
    }

  /** Replaces every `collection(index)` in `body` with a fresh element name,
    * and reports whether the index survives the substitution.
    *
    * Declines when the body subscripts the collection by anything other than
    * the loop variable: `xs(i - 1)` reads a neighbour, which `zipWithIndex`
    * does not hand over and no Cats typeclass expresses.
    *
    * The replacement is spliced into the body's own source text rather than
    * rendered from a rewritten tree. Scalameta re-prints a transformed tree
    * from its structure, so a substitution inside a string interpolation comes
    * back reflowed across three lines. Splicing keeps every byte the author
    * wrote except the subscripts themselves.
    */
  private def substituteElement(
      body: Term,
      collection: Term,
      index: String
  ): Option[(String, String, Boolean)] = {
    val subscripts = body.collect {
      case subscript @ Subscript(receiver, argument)
          if sameTerm(receiver, collection) =>
        subscript -> argument
    }
    val reached = subscripts.filter { case (_, argument) =>
      isName(argument, index)
    }
    val neighbours = subscripts.sizeIs > reached.size
    Option.when(reached.nonEmpty && !neighbours) {
      val element = freshName("x", body)
      val origin = body.pos.start
      val spliced = reached
        .map { case (subscript, _) => subscript.pos }
        .sortBy(_.start)
        .foldRight(body.pos.text) { (position, text) =>
          text.patch(
            position.start - origin,
            element,
            position.end - position.start
          )
        }
      (spliced, element, occursOutsideSubscripts(body, index, reached.size))
    }
  }

  /** Whether the index is still read once its subscripts are gone. */
  private def occursOutsideSubscripts(
      body: Term,
      index: String,
      substituted: Int
  ): Boolean =
    body.collect { case Term.Name(`index`) => () }.sizeIs > substituted

  /** `xs.foldLeft(F.pure(z))((acc, x) => acc.flatMap(s => body))` ->
    * `xs.foldM(z)((s, x) => body)`.
    *
    * `foldM` is the named form of exactly this: a fold whose step is effectful.
    * The hand-written version has to keep the effect in the accumulator and
    * unwrap it every step, which is the part that reads as machinery.
    */
  private def foldM(term: Term, facts: CatsFacts): Option[IdiomRewrite] =
    term match {
      case CurriedCall(collection, "foldLeft", seed, step) =>
        for {
          zero <- pureValue(seed, facts)
          function <- Some(step).collect { case function: Term.Function =>
            function
          }
          (accumulator, element) <- Some(function.paramClause.values).collect {
            case List(accumulator, element) => accumulator -> element
          }
          accumulatorName <- namedParam(accumulator)
          (state, body) <- flatMapOn(function.body, accumulatorName)
          if !references(body, accumulatorName)
        } yield IdiomRewrite(
          term,
          s"${collection.syntax}.foldM(${zero.syntax})(($state, ${element.syntax}) => ${body.syntax})",
          needsCatsSyntax = true
        )
      case _ =>
        None
    }

  /** `<name>.flatMap(s => body)`, with the bound state and the body. */
  private def flatMapOn(term: Term, name: String): Option[(String, Term)] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(receiver, Term.Name("flatMap"))
              if isName(receiver, name) =>
            for {
              function <- singleArg(apply.argClause.values).collect {
                case function: Term.Function => function
              }
              param <- singleParam(function)
              state <- namedParam(param)
            } yield state -> function.body
          case _ =>
            None
        }
      case _ =>
        None
    }

  /** The lifted value of `x.pure[F]` or `Typeclass[F].pure(x)`. */
  private def pureValue(term: Term, facts: CatsFacts): Option[Term] =
    term match {
      case applyType: Term.ApplyType =>
        applyType.fun match {
          case Term.Select(value, Term.Name("pure")) => Some(value)
          case _                                     => None
        }
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(receiver, Term.Name("pure"))
              if isTypeclassApply(receiver, facts) =>
            singleArg(apply.argClause.values)
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def isTypeclassApply(term: Term, facts: CatsFacts): Boolean =
    facts.typeclassObject(term).exists(CatsFacts.Typeclasses.pure)

  /** `xs.indices`, `0 until xs.length`, `0 until xs.size`. */
  private object IndexRange {
    def unapply(term: Term): Option[Term] =
      term match {
        case Term.Select(collection, Term.Name("indices")) =>
          Some(collection)
        case infix: Term.ApplyInfix if infix.op.value == "until" =>
          for {
            _ <- Some(infix.lhs).collect { case Lit.Int(0) => () }
            bound <- singleArg(infix.argClause.values)
            collection <- Some(bound).collect {
              case Term.Select(collection, Term.Name(name))
                  if SizeMethods.contains(name) =>
                collection
            }
          } yield collection
        case _ =>
          None
      }
  }

  private object Subscript {
    def unapply(tree: Tree): Option[(Term, Term)] =
      tree match {
        case apply: Term.Apply =>
          apply.fun match {
            case _: Term.Select | _: Term.Name =>
              singleArg(apply.argClause.values).map(apply.fun -> _)
            case _ =>
              None
          }
        case _ =>
          None
      }
  }

  private val IterationMethods: Set[String] = Set("map", "foreach", "flatMap")

  private val SizeMethods: Set[String] = Set("length", "size")

  private def isName(term: Term, name: String): Boolean =
    term match {
      case Term.Name(`name`) => true
      case _                 => false
    }

  private def sameTerm(left: Term, right: Term): Boolean =
    left.syntax == right.syntax
}
