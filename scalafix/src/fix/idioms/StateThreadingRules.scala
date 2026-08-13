package fix.idioms

import scala.meta._

/** Folds and methods that thread a state by hand.
  *
  * `foldLeft` over a pair is the `State` monad written out: one half of the
  * accumulator is carried forward, the other is appended to. So is a method
  * shaped `(S, A) => (S, B)` -- that is `State[S, B]`'s signature with the
  * state spelled twice, and `Ref#modifyState` takes exactly that.
  *
  * Only the fold rewrites, and only when its seed says what the state type is.
  * The rest report. The difference is not caution for its own sake: turning
  * `(S, A) => (S, B)` into `State[S, B]` changes a signature, and a signature
  * changes at every call site, which is a project-wide decision this rule is
  * not handed the information to make (`docs/RULES.md`).
  */
private[fix] object StateThreadingRules {

  import TermShapes._

  val ThreadedFold: String =
    "this `foldLeft` threads a state through a pair. It is `traverse` in " +
      "`State`, with the carried half as the state and the appended half as " +
      "the value."

  val StateShapedMethod: String =
    "`(S, A) => (S, B)` is `State[S, B]` with the state written twice. " +
      "`Ref#modifyState` takes a `State` directly."

  val SelfRecursiveEffect: String =
    "this effect polls until a condition holds. `iterateUntilM` / " +
      "`iterateWhile` names the loop."

  val SelfRecursiveRetry: String =
    "this effect retries itself on failure. That is a retry policy, not a " +
      "fold: `iterateUntilM` does not express the give-up condition."

  def rewrites(tree: Tree): List[IdiomRewrite] =
    tree.collect { case term: Term =>
      threadedFold(term)
    }.flatten

  def findings(tree: Tree, stateT: Boolean): List[IdiomFinding] =
    tree.collect {
      case term: Term
          if stateT && threadsPair(term) && threadedFold(term).isEmpty =>
        List(IdiomFinding(term, ThreadedFold))
      case defn: Defn.Def if isStateShaped(defn)(tree) =>
        List(IdiomFinding(defn, StateShapedMethod))
      case defn: Defn.Def if recursesOnItself(defn) =>
        List(
          IdiomFinding(
            defn,
            if (isRetry(defn)) SelfRecursiveRetry else SelfRecursiveEffect
          )
        )
    }.flatten

  /** `xs.foldLeft((s0, empty)) { case ((s, out), x) => (s1, out :+ b) }` ->
    * `xs.traverse(x => State((s: S) => (s1, b))).run(s0).value`.
    *
    * The output half has to be write-only -- appended to and never read -- for
    * `traverse` to be able to build it. A fold that reads what it has already
    * collected is not a `traverse`, and this declines it.
    */
  private def threadedFold(term: Term): Option[IdiomRewrite] =
    term match {
      case CurriedCall(
            collection,
            "foldLeft",
            Term.Tuple(List(seed, empty)),
            step
          ) =>
        for {
          stateType <- renderedType(seed)
          _ <- Option.when(isEmptyCollection(empty))(())
          fold <- pairFold(step)
          (state, output, element, body) = fold
          (_, appended) <- finalPair(body)
          value <- appendedValue(appended, output)
          if !readsOutput(body, output, appended)
        } yield IdiomRewrite(
          term,
          s"${collection.syntax}.traverse($element => " +
            s"State(($state: $stateType) => " +
            s"${rebuilt(body, appended, value)}))" +
            s".run(${seed.syntax}).value",
          needsCatsSyntax = true
        )
      case _ =>
        None
    }

  /** Whether a fold threads a pair at all, rewritable or not. */
  private def threadsPair(term: Term): Boolean =
    term match {
      case CurriedCall(_, "foldLeft", Term.Tuple(List(_, _)), step) =>
        pairFold(step).isDefined
      case _ =>
        false
    }

  /** `{ case ((s, out), x) => body }`.
    *
    * Only the destructuring spelling. A plain `(acc, x) => …` lambda reads the
    * accumulator through `._1` / `._2`, and the halves are then not separable
    * without deciding what each projection meant.
    */
  private def pairFold(step: Term): Option[(String, String, String, Term)] =
    step match {
      case Term.PartialFunction(List(Case(pattern, None, body))) =>
        pairPattern(pattern).map { case (state, output, element) =>
          (state, output, element, body)
        }
      case _ =>
        None
    }

  private def pairPattern(pattern: Pat): Option[(String, String, String)] =
    pattern match {
      case Pat.Tuple(
            List(
              Pat.Tuple(
                List(Pat.Var(Term.Name(state)), Pat.Var(Term.Name(output)))
              ),
              Pat.Var(Term.Name(element))
            )
          ) =>
        Some((state, output, element))
      case _ =>
        None
    }

  /** The `(carried, appended)` the body ends in. */
  private def finalPair(body: Term): Option[(Term, Term)] =
    lastExpression(body) match {
      case Term.Tuple(List(carried, appended)) => Some(carried -> appended)
      case _                                   => None
    }

  private def lastExpression(body: Term): Term =
    body match {
      case Term.Block(statements) =>
        statements.lastOption
          .collect { case term: Term => term }
          .getOrElse(body)
      case other =>
        other
    }

  /** The element appended to the output half, for `out :+ b` and `b +: out`. */
  private def appendedValue(appended: Term, output: String): Option[Term] =
    appended match {
      case infix: Term.ApplyInfix if infix.op.value == ":+" =>
        Option
          .when(isName(infix.lhs, output))(singleArg(infix.argClause.values))
          .flatten
      case infix: Term.ApplyInfix if infix.op.value == "+:" =>
        singleArg(infix.argClause.values)
          .filter(rest => isName(rest, output))
          .map(_ => infix.lhs)
      case _ =>
        None
    }

  /** Whether the output half is read anywhere except the append itself. */
  private def readsOutput(body: Term, output: String, appended: Term): Boolean =
    body.collect { case Term.Name(`output`) => () }.sizeIs >
      appended.collect { case Term.Name(`output`) => () }.size

  /** The body with its final pair narrowed from `(s, out :+ b)` to `(s, b)`. */
  private def rebuilt(body: Term, appended: Term, value: Term): String = {
    val origin = body.pos.start
    val replaced = body.pos.text.patch(
      appended.pos.start - origin,
      value.syntax,
      appended.pos.end - appended.pos.start
    )
    if (body.is[Term.Block]) s"{$replaced}" else replaced
  }

  /** An empty collection literal, which is what `traverse` starts from. */
  private def isEmptyCollection(term: Term): Boolean =
    term match {
      case Term.Name("Nil")                   => true
      case Term.Select(_, Term.Name("empty")) => true
      case Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name("empty")), _) =>
        true
      case apply: Term.Apply =>
        apply.argClause.values.isEmpty && isEmptyCollection(apply.fun)
      case _ =>
        false
    }

  /** The type a fold seed announces: `Map.empty[K, V]` says `Map[K, V]`. */
  private def renderedType(seed: Term): Option[String] =
    seed match {
      case applyType: Term.ApplyType =>
        applyType.fun match {
          case Term.Select(container, Term.Name("empty"))
              if applyType.targClause.values.nonEmpty =>
            val arguments =
              applyType.targClause.values.map(_.syntax).mkString(", ")
            Some(s"${container.syntax}[$arguments]")
          case _ =>
            None
        }
      case Lit.Int(_)    => Some("Int")
      case Lit.Long(_)   => Some("Long")
      case Lit.Double(_) => Some("Double")
      case Lit.String(_) => Some("String")
      case _             => None
    }

  /** `def f(s: S, a: A): (S, B)` -- `State[S, B]` with the state spelled out.
    *
    * The shape alone is not enough. `priceKey(agent: String, model: String):
    * (String, String)` has it and threads nothing; so does any function that
    * pairs, keys or tags. What makes it a state transition is that something
    * *runs* it as one, so this additionally requires a call site feeding it to
    * `Ref#modify` or to a fold -- which is exactly where the rewrite would
    * become `modifyState`.
    */
  private def isStateShaped(defn: Defn.Def)(scope: Tree): Boolean =
    hasStateShape(defn) && threadedBy(defn.name.value, scope)

  private def hasStateShape(defn: Defn.Def): Boolean = {
    val parameters = defn.paramClauseGroups
      .flatMap(_.paramClauses)
      .flatMap(_.values)
      .flatMap(_.decltpe)
    (defn.decltpe, parameters) match {
      case (Some(Type.Tuple(List(result, _))), first :: _) =>
        result.syntax == first.syntax
      case _ =>
        false
    }
  }

  /** Whether anything in scope runs this definition as a state transition. */
  private def threadedBy(name: String, scope: Tree): Boolean =
    scope.collect {
      case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(consumer)), args)
          if StateConsumers.contains(consumer) &&
            args.values.exists(argument => references(argument, name)) =>
        ()
    }.nonEmpty

  private val StateConsumers: Set[String] =
    Set("modify", "modifyState", "foldLeft", "foldRight", "mapAccumulate")

  /** Whether the recursion is a retry rather than a poll.
    *
    * A retry recurses out of an error handler, or counts down a budget --
    * `attempt < Max`, `retriesLeft > 0`. Neither is a fold over a condition,
    * and `iterateUntilM` has nowhere to put the giving up.
    */
  private def isRetry(defn: Defn.Def): Boolean =
    defn.body.collect {
      case Term.Select(_, Term.Name(handler)) if ErrorHandlers(handler) => ()
    }.nonEmpty || countsDown(defn)

  private val ErrorHandlers: Set[String] =
    Set("handleErrorWith", "recoverWith", "handleError", "recover", "onError")

  /** A comparison between one of the definition's parameters and a bound. */
  private def countsDown(defn: Defn.Def): Boolean = {
    val parameters = defn.paramClauseGroups
      .flatMap(_.paramClauses)
      .flatMap(_.values)
      .flatMap(param => namedParam(param))
      .toSet
    defn.body.collect {
      case infix: Term.ApplyInfix
          if Comparisons(infix.op.value) &&
            parameters.exists(parameter => references(infix.lhs, parameter)) =>
        ()
    }.nonEmpty
  }

  private val Comparisons: Set[String] = Set("<", ">", "<=", ">=")

  /** A def whose body is an effect that either stops or goes round again.
    *
    * The shape is a poll loop: read something, and on one branch of a condition
    * call yourself. `iterateUntilM` is that loop with a name.
    *
    * The recursive call has to be the *tail* of an `if` branch, and the result
    * has to be an effect. Without both, this matches every ordinary recursive
    * function -- a parser, a tree walk, a null-guard chain -- none of which is
    * a loop over an effect, and all of which showed up when the check was
    * merely "calls itself somewhere and contains an `if`".
    */
  private def recursesOnItself(defn: Defn.Def): Boolean =
    sequencesAnEffect(defn) && !isTailRecursive(defn) && defn.body.collect {
      case branch: Term.If
          if tailCalls(branch.thenp, defn.name.value) ||
            tailCalls(branch.elsep, defn.name.value) =>
        ()
    }.nonEmpty

  /** Whether the body sequences at all.
    *
    * A declared `F[A]` result is not enough -- `Vector[Glitch]` has that shape
    * too, and a `@tailrec` loop over an index returning one is not a loop over
    * an effect. What distinguishes the poll loop is that it *binds*: the
    * recursion happens after a `flatMap` or a `>>`.
    */
  private def sequencesAnEffect(defn: Defn.Def): Boolean =
    defn.body.collect {
      case Term.Select(_, Term.Name("flatMap"))                         => ()
      case Term.Select(_, Term.Name(handler)) if ErrorHandlers(handler) => ()
      case infix: Term.ApplyInfix if Binds(infix.op.value)              => ()
    }.nonEmpty

  private val Binds: Set[String] = Set(">>", "*>", ">>=")

  /** `@tailrec` says the author already chose the loop form. */
  private def isTailRecursive(defn: Defn.Def): Boolean =
    defn.mods.exists(_.syntax.contains("tailrec"))

  /** Whether the branch's last expression is, or applies, `name`. */
  private def tailCalls(branch: Term, name: String): Boolean =
    lastExpression(branch) match {
      case Term.Name(`name`) => true
      case apply: Term.Apply => isCallTo(apply.fun, name)
      case infix: Term.ApplyInfix =>
        infix.argClause.values.exists(argument => tailCalls(argument, name))
      case _ => false
    }

  private def isCallTo(fun: Term, name: String): Boolean =
    fun match {
      case Term.Name(`name`) => true
      case apply: Term.Apply => isCallTo(apply.fun, name)
      case _                 => false
    }

  private def isName(term: Term, name: String): Boolean =
    term match {
      case Term.Name(`name`) => true
      case _                 => false
    }
}
