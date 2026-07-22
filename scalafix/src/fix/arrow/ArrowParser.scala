package fix.arrow

import scala.meta._

import scalafix.v1._

import fix.arrow.ArrowIR._

/** Parses the body of a `Kleisli { x => ... }` lambda into an [[ArrowIR]].
  *
  * The input `x` is identified by its *symbol*, not its spelling, so "is this
  * the arrow's input" and "is this the previous step's result" are answered the
  * way `docs/RULES.md` requires -- two bindings that share a name are still two
  * bindings. That symbol-level threading is exactly what the shipped matchers
  * lacked, and the reason they could not go past a two-step chain or an
  * arity-two fan-out.
  *
  * Anything the parser does not recognise becomes [[Opaque]] rather than a
  * `None`, so a body that is *mostly* analysable still produces a tree; the
  * readability budget then decides whether the un-analysed part is small enough
  * to leave the rewrite worthwhile (in practice: it is not, and the site is
  * declined).
  */
object ArrowParser {

  /** A single effectful step: `binding <- callee.run(arg)`. `callee` is the
    * Kleisli receiver; `argSymbol` is the symbol of the value fed to it, which
    * decides whether steps chain (each consumes the previous) or fan out (all
    * consume the same input). `argExpr` is the full argument, which may be a
    * *projection* of that value (`task.run.worktreePath`) rather than the bare
    * binding -- captured so a fan-out branch can emit a `.local` reshape.
    */
  private final case class Step(
      bindingSymbol: Symbol,
      bindingName: String,
      callee: Term,
      argSymbol: Symbol,
      argExpr: Term
  )

  /** The arrow input's name and declared type, used to emit a fully typed
    * projection lambda for a `.local` -- `(request: Request) => request.path`
    * -- since `Kleisli.local`'s new input type is otherwise inferred as `Any`.
    * Both are needed to emit a projected fan-out; without them, projected
    * branches are not offered.
    */
  final case class Input(
      name: String,
      tpe: String,
      effect: Option[String] = None
  )

  def parse(
      body: Term,
      inputSymbol: Symbol
  )(implicit doc: SemanticDocument): Option[ArrowIR] =
    parse(body, inputSymbol, None, aggressive = false)

  def parse(
      body: Term,
      inputSymbol: Symbol,
      input: Option[Input]
  )(implicit doc: SemanticDocument): Option[ArrowIR] =
    parse(body, inputSymbol, input, aggressive = false)

  def parse(
      body: Term,
      inputSymbol: Symbol,
      input: Option[Input],
      aggressive: Boolean
  )(implicit doc: SemanticDocument): Option[ArrowIR] =
    peelTrailingMap(body) match {
      // `<effects>.map(fn)` -- pattern B and the tail of a chain that ends in a
      // pure reshape. Parse the effect part, then reshape its output.
      case Some((inner, fn)) =>
        // The reshape must not close over the arrow input; point-free output
        // has no name to bind it to, so a capturing `map` would be dropped.
        if (referencesAny(fn, List(inputSymbol))) None
        else parseSpine(inner, inputSymbol, input, aggressive).map(Rmap(_, fn))
      case None =>
        parseSpine(body, inputSymbol, input, aggressive)
    }

  /** Splits `receiver.map(fn)` into `(receiver, fn)` when `fn` is a function
    * literal or placeholder and `receiver` is not itself already a Kleisli's
    * own `.map` (which would make the rewrite a no-op that still edits,
    * breaking idempotence).
    */
  private def peelTrailingMap(
      term: Term
  )(implicit doc: SemanticDocument): Option[(Term, Term)] =
    term match {
      case Term.Apply.After_4_6_0(
            Term.Select(receiver, Term.Name("map")),
            Term.ArgClause(List(fn), _)
          ) if !KleisliType.isKleisli(receiver) =>
        Some((receiver, fn))
      case _ =>
        None
    }

  private def parseSpine(
      body: Term,
      inputSymbol: Symbol,
      input: Option[Input],
      aggressive: Boolean
  )(implicit doc: SemanticDocument): Option[ArrowIR] =
    choiceArrow(body, inputSymbol).orElse {
      val (steps, yieldTerm) = spine(body)
      val conservative =
        if (steps.isEmpty) bareEffect(body, inputSymbol)
        else classify(steps, yieldTerm, inputSymbol, input)
      conservative.orElse(
        if (aggressive) aggressiveFor(body, inputSymbol, input) else None
      )
    }

  /** Aggressive fan-out (opt-in via `PreferArrow.aggressive`). Where the
    * conservative paths decline because a generator calls a *plain* effectful
    * method rather than an existing Kleisli -- `dc <- GitHub.dep(x.a, x.b)` --
    * lift each generator's right-hand side into `Kleisli { (x: T) => rhs }` and
    * fan the independent generators out with `&&&`. When the `yield` still
    * references the input, retain it with a leading `Kleisli.ask`. The result
    * is provably equivalent (independent generators commute under the same
    * input; `&&&` on `Kleisli` sequences left-then-right exactly as the `for`
    * did) but often busier than the source -- which is why it is gated behind
    * the flag rather than on by default.
    *
    * Restricted to a `for` of plain generators: no `val` binders, no guards, no
    * generator depending on another's binding (that needs `flatMap`, not
    * `&&&`). Requires the input's name, type and effect, since the lifted
    * lambdas and the typed `ask` all need them.
    */
  private def aggressiveFor(
      body: Term,
      inputSymbol: Symbol,
      input: Option[Input]
  )(implicit doc: SemanticDocument): Option[ArrowIR] =
    for {
      in <- input
      effect <- in.effect
      gens <- body match {
        case Term.ForYield.After_4_9_9(enums, yieldBody) =>
          splitGenerators(enums.enums).map((_, yieldBody))
        case _ => None
      }
      (split, yieldBody) = gens
      ir <- buildAggressiveFanOut(
        split,
        yieldBody,
        inputSymbol,
        in,
        effect
      )
    } yield ir

  /** A `for` split into the discard generators that run before the work, the
    * named generators themselves, and the discard generators that run after.
    */
  private final case class Split(
      leading: List[Term],
      named: List[(Term.Name, Term)],
      trailing: List[Term]
  )

  /** Every enumerator is a bare generator -- `name <- rhs` or `_ <- rhs` -- and
    * the discards form a prefix and a suffix around the named ones. Any `val`
    * binder or guard makes this `None`, as does a discard *between* two named
    * generators: `&&&` feeds both its arms the same input and has no position
    * to put an interleaved effect in, and reordering it around a neighbour
    * would change the sequence the `for` specified.
    */
  private def splitGenerators(enums: List[Enumerator]): Option[Split] = {
    val classified = traverse(enums) {
      case Enumerator.Generator(Pat.Var(name), rhs)  => Some(Right((name, rhs)))
      case Enumerator.Generator(Pat.Wildcard(), rhs) => Some(Left(rhs))
      case _                                         => None
    }

    classified.flatMap { items =>
      val leading = items.takeWhile(_.isLeft).collect { case Left(rhs) => rhs }
      val rest = items.drop(leading.length)
      val named = rest.takeWhile(_.isRight).collect { case Right(pair) => pair }
      val tail = rest.drop(named.length)

      // Anything after the first trailing discard must also be a discard;
      // a named generator there means an interleaved effect.
      Option.when(tail.forall(_.isLeft))(
        Split(leading, named, tail.collect { case Left(rhs) => rhs })
      )
    }
  }

  private def buildAggressiveFanOut(
      split: Split,
      yieldBody: Term,
      inputSymbol: Symbol,
      in: Input,
      effect: String
  )(implicit doc: SemanticDocument): Option[ArrowIR] = {
    val generators = split.named
    val bindingSymbols = generators.map(_._1.symbol)
    // Fan-out demands the generators be mutually independent: no right-hand
    // side may reference another generator's binding, and every one must be a
    // function of the arrow input (so lifting it into `Kleisli { x => rhs }` is
    // meaningful). A generator referencing a prior binding needs `flatMap`
    // sequencing, which this path deliberately does not fake.
    val independent =
      generators.forall { case (_, rhs) =>
        !referencesAny(rhs, bindingSymbols)
      } &&
        // A leading discard runs before any binding exists, so it cannot read
        // one either.
        split.leading.forall(rhs => !referencesAny(rhs, bindingSymbols))
    // At least one generator must be a function of the arrow input. A fan-out
    // where *no* branch reads the input is a plain `mapN` over constants and
    // gains nothing from being an arrow; branches that ignore the input are
    // fine alongside ones that read it -- `Kleisli { _ => c }` is still the same
    // effect in the same position.
    val anyReferencesInput =
      (split.leading ++ generators.map(_._2)).exists(rhs =>
        referencesAny(rhs, List(inputSymbol))
      )
    // A trailing discard is rendered inside `.flatTap`, where the arrow input
    // is out of scope -- only the arms' results are bound there. One that reads
    // the input would need the input threaded through the tuple as well, which
    // costs more plumbing than the `for` it replaces, so it is declined.
    val trailingClosed =
      split.trailing.forall(rhs => !referencesAny(rhs, List(inputSymbol)))
    // Two effects is the point of the exercise; one is not a composition. A
    // discard counts, so a single named generator behind a `_ <- log(...)` is
    // enough -- that shape is exactly what `*>` exists for.
    val effectArms =
      split.leading.length + generators.length + split.trailing.length
    if (
      generators.isEmpty || effectArms < 2 || !independent ||
      !anyReferencesInput || !trailingClosed
    ) None
    else {
      val arms: List[ArrowIR] =
        generators.map { case (_, rhs) => LiftK(in.name, in.tpe, rhs) }
      val inputRetained = referencesAny(yieldBody, List(inputSymbol))
      val allArms =
        if (inputRetained) Ask(effect, in.tpe) :: arms else arms
      val merged = allArms.reduceLeft(Merge(_, _))
      val names =
        (if (inputRetained) List(in.name) else Nil) ++ generators.map(
          _._1.value
        )
      // `flatTap` keeps the value it taps, so several trailing discards stack
      // with the same binders and the reshape below still sees the arms.
      val tapped = split.trailing.foldLeft(merged) { (acc, rhs) =>
        FlatTap(acc, names, Eff(liftF(rhs)))
      }
      // A single arm produces its own value, two produce a flat `(a, b)` -- in
      // neither case is there nesting to unpack, so a `yield` that names the
      // arms in arm order is already the arrow's output and the reshape would
      // be the identity. Emitting it would be noise, and would also break
      // idempotence-by-inspection for a reader.
      val armSymbols =
        (if (inputRetained) List(inputSymbol) else Nil) ++ bindingSymbols
      val reshaped =
        if (isIdentityYield(yieldBody, armSymbols)) tapped
        else Rmap(tapped, destructureFunction(names, yieldBody))
      // Leading discards keep their source order ahead of the work. They are
      // lifted with an explicit parameter type rather than `Kleisli.liftF`
      // because as the *left* operand of `*>` they are what the input type is
      // inferred from, and `liftF` there would leave it unconstrained.
      Some(split.leading.foldRight(reshaped) { (rhs, acc) =>
        ProductR(LiftK(in.name, in.tpe, rhs), acc)
      })
    }
  }

  /** `Kleisli.liftF(rhs)` -- a plain `F[_]` as an arrow that ignores its input.
    * Only emitted where the expected type is already known (inside a
    * `.flatTap`), since `liftF` infers its input type from context.
    */
  private def liftF(rhs: Term): Term =
    Term.Apply.After_4_6_0(
      Term.Select(Term.Name("Kleisli"), Term.Name("liftF")),
      Term.ArgClause(List(rhs))
    )

  /** A `yield` that just names the arms, in arm order -- so the reshape it
    * would produce is the identity. Only the un-nested arities: beyond two arms
    * the arrow's output is a left-nested tuple and a flat `yield (a, b, c)` is
    * a genuine reshape.
    */
  private def isIdentityYield(term: Term, symbols: List[Symbol])(implicit
      doc: SemanticDocument
  ): Boolean =
    symbols match {
      case List(only) =>
        term match {
          case name: Term.Name => name.symbol == only
          case _               => false
        }
      case List(_, _) => isTupleOf(term, symbols)
      case _          => false
    }

  /** `yield (a, b)` written exactly as the arms' own bindings, in arm order --
    * compared by symbol, so a name that merely *spells* like an arm but
    * resolves elsewhere is not mistaken for it.
    */
  private def isTupleOf(term: Term, symbols: List[Symbol])(implicit
      doc: SemanticDocument
  ): Boolean =
    term match {
      case Term.Tuple(elements) =>
        elements.length == symbols.length &&
        elements.zip(symbols).forall {
          case (name: Term.Name, expected) => name.symbol == expected
          case _                           => false
        }
      case _ => false
    }

  /** A `{ case ((a, b), c) => body }` that unpacks the left-nested tuple a
    * chain of `&&&` produces, binding each arm's result to the name the source
    * `for` used, so the original `yield` body can be reused verbatim.
    */
  private def destructureFunction(names: List[String], body: Term): Term = {
    val nested = names
      .map(n => Pat.Var(Term.Name(n)): Pat)
      .reduceLeft((acc, p) => Pat.Tuple(List(acc, p)))
    Term.PartialFunction(List(Case(nested, None, body)))
  }

  /** The clean `ArrowChoice` shape: a Kleisli producing an `Either` whose two
    * arms are each a Kleisli applied to the matched value --
    * `k.run(x).flatMap { case Left(l) => kl.run(l); case Right(r) => kr.run(r) }`
    * -- collapses to `k >>> (kl ||| kr)`. `|||` routes an `Either` input to the
    * left or right arrow, preserving the branch order and short-circuiting
    * exactly as the `flatMap` did.
    *
    * Restricted to this one sub-shape on purpose: `Either` scrutinee, both arms
    * bare Kleisli applications of the matched binding. Anything else (a guard,
    * a third case, an arm that is not a lone Kleisli call) is left to the other
    * paths or declined.
    */
  private def choiceArrow(
      body: Term,
      inputSymbol: Symbol
  )(implicit doc: SemanticDocument): Option[ArrowIR] =
    body match {
      case Term.Apply.After_4_6_0(
            Term.Select(scrutinee, Term.Name("flatMap")),
            Term.ArgClause(List(pf: Term.PartialFunction), _)
          ) =>
        for {
          scrutineeCallee <- inputKleisli(scrutinee, inputSymbol)
          (leftArm, rightArm) <- eitherArms(pf.cases)
        } yield AndThen(Eff(scrutineeCallee), Choice(leftArm, rightArm))
      case _ =>
        None
    }

  /** A `k.run(input)` / `k(input)` where `k` is a Kleisli fed the arrow input.
    */
  private def inputKleisli(
      term: Term,
      inputSymbol: Symbol
  )(implicit doc: SemanticDocument): Option[Term] =
    runArgument(term).collect {
      case (callee, arg)
          if arg.symbol == inputSymbol && KleisliType.isKleisli(callee) =>
        callee
    }

  /** Two cases `case Left(l) => kl.run(l)` and `case Right(r) => kr.run(r)` as
    * `(Eff(kl), Eff(kr))`, requiring each arm to be a bare Kleisli application
    * of the value that case bound and to be in Left-then-Right order.
    */
  private def eitherArms(
      cases: List[Case]
  )(implicit doc: SemanticDocument): Option[(ArrowIR, ArrowIR)] =
    cases match {
      case List(left, right) =>
        for {
          (leftVar, leftBody) <- singleExtractCase(left, "Left")
          (rightVar, rightBody) <- singleExtractCase(right, "Right")
          leftK <- armKleisli(leftBody, leftVar)
          rightK <- armKleisli(rightBody, rightVar)
        } yield (Eff(leftK), Eff(rightK))
      case _ =>
        None
    }

  /** A `case Ctor(v) => body` with no guard, returning the bound variable's
    * symbol and the body -- when `Ctor` is the expected `Left`/`Right`.
    */
  private def singleExtractCase(
      c: Case,
      ctorName: String
  )(implicit doc: SemanticDocument): Option[(Symbol, Term)] =
    c match {
      case Case(extract: Pat.Extract, None, body)
          if isNamed(extract.fun, ctorName) =>
        extract.argClause.values match {
          case List(Pat.Var(v)) => Some((v.symbol, body))
          case _                => None
        }
      case _ =>
        None
    }

  private def isNamed(term: Term, name: String): Boolean =
    term match {
      case Term.Name(`name`)                 => true
      case Term.Select(_, Term.Name(`name`)) => true
      case _                                 => false
    }

  private def armKleisli(
      body: Term,
      boundSymbol: Symbol
  )(implicit doc: SemanticDocument): Option[Term] =
    runArgument(body).collect {
      case (callee, arg)
          if arg.symbol == boundSymbol && KleisliType.isKleisli(callee) =>
        callee
    }

  /** A body that is a single Kleisli applied to the arrow input, `k.run(x)` --
    * the base case a trailing `.map` reshapes.
    */
  private def bareEffect(
      body: Term,
      inputSymbol: Symbol
  )(implicit doc: SemanticDocument): Option[ArrowIR] =
    runArgument(body) match {
      case Some((callee, arg))
          if arg.symbol == inputSymbol && KleisliType.isKleisli(callee) =>
        Some(Eff(callee))
      case _ =>
        None
    }

  /** Flattens a monadic body into its effect steps and its trailing value.
    *
    * Handles both surface forms: a `for`-comprehension and the desugared
    * `flatMap`/`map` chain it lowers to. The last generator of a `for` and the
    * outermost `map`/final call of a chain both land in `yieldTerm`.
    */
  private def spine(
      body: Term
  )(implicit doc: SemanticDocument): (List[Step], Term) =
    body match {
      case Term.ForYield.After_4_9_9(enumerators, yieldBody) =>
        forSpine(enumerators.enums, yieldBody)
      case other =>
        flatMapSpine(other)
    }

  private def forSpine(
      enumerators: List[Enumerator],
      yieldBody: Term
  )(implicit doc: SemanticDocument): (List[Step], Term) =
    enumerators match {
      case Enumerator.Generator(Pat.Var(bound), rhs) :: rest =>
        stepOf(bound, rhs) match {
          case Some(step) =>
            val (restSteps, yieldTerm) = forSpine(rest, yieldBody)
            (step :: restSteps, yieldTerm)
          case None =>
            (Nil, yieldBody)
        }
      case _ =>
        (Nil, yieldBody)
    }

  private def flatMapSpine(
      term: Term
  )(implicit doc: SemanticDocument): (List[Step], Term) =
    term match {
      case applyTerm: Term.Apply =>
        applyTerm.fun match {
          case Term.Select(receiver, Term.Name("flatMap")) =>
            generatorStep(receiver, applyTerm) match {
              case Some((step, fn)) =>
                val (rest, yieldTerm) = flatMapSpine(fn.body)
                (step :: rest, yieldTerm)
              case None =>
                (Nil, term)
            }
          // A terminal `k.run(x).map(v => body)` is the last generator of a
          // desugared `for`: `v <- k.run(x)` then `yield body`. This is how the
          // 2-step fan-out `k1.run(x).flatMap(a => k2.run(x).map(b => (a, b)))`
          // presents once the outer `flatMap` is peeled -- the final effect is
          // a `.map` receiver, not a generator.
          case Term.Select(receiver, Term.Name("map"))
              if namedFunctionStep(receiver, applyTerm).isDefined =>
            val (step, fn) = namedFunctionStep(receiver, applyTerm).get
            (List(step), fn.body)
          case _ =>
            (Nil, term)
        }
      case _ =>
        (Nil, term)
    }

  /** A `receiver.op(v => body)` where `receiver` is `k.run(arg)`, as a `Step`
    * binding `v` plus the function. Shared by the `flatMap` and terminal-`map`
    * cases.
    */
  private def generatorStep(
      receiver: Term,
      applyTerm: Term.Apply
  )(implicit doc: SemanticDocument): Option[(Step, Term.Function)] =
    (singleFunction(applyTerm), runArgAny(receiver)) match {
      case (Some(fn), Some((callee, argExpr))) =>
        (fn.paramClause.values, argRoot(argExpr)) match {
          case (List(param), Some(root)) =>
            Some(
              (
                Step(
                  param.name.symbol,
                  param.name.value,
                  callee,
                  root,
                  argExpr
                ),
                fn
              )
            )
          case _ => None
        }
      case _ => None
    }

  private def namedFunctionStep(
      receiver: Term,
      applyTerm: Term.Apply
  )(implicit doc: SemanticDocument): Option[(Step, Term.Function)] =
    generatorStep(receiver, applyTerm).filter { case (_, fn) =>
      singleFunctionParamName(fn).isDefined
    }

  private def singleFunctionParamName(fn: Term.Function): Option[String] =
    fn.paramClause.values match {
      case List(param) if param.name.value != "_" => Some(param.name.value)
      case _                                      => None
    }

  private def stepOf(bound: Term.Name, rhs: Term)(implicit
      doc: SemanticDocument
  ): Option[Step] =
    runArgAny(rhs).flatMap { case (callee, argExpr) =>
      argRoot(argExpr).map { root =>
        Step(bound.symbol, bound.value, callee, root, argExpr)
      }
    }

  /** The receiver and single `Term.Name` argument of a Kleisli application:
    * `k.run(x)`, `k.apply(x)` or the bare `k(x)`. The receiver's Kleisli-ness
    * is checked later, at [[calleeIfKleisli]], since a step can also be a plain
    * effectful call that is not a Kleisli at all.
    */
  private def runArgument(term: Term): Option[(Term, Term.Name)] =
    term match {
      case Term.Apply.After_4_6_0(
            Term.Select(receiver, Term.Name("run" | "apply")),
            Term.ArgClause(List(arg: Term.Name), _)
          ) =>
        Some((receiver, arg))
      case Term.Apply.After_4_6_0(
            callee,
            Term.ArgClause(List(arg: Term.Name), _)
          ) =>
        Some((callee, arg))
      case _ =>
        None
    }

  /** Like [[runArgument]] but the single argument may be any expression, not
    * only a bare name -- `k.run(task.run.worktreePath)` -- and a bare Kleisli
    * application may carry *several* arguments, which Scala auto-tuples into
    * the Kleisli's tuple input: `k(a, b, c)` becomes the tuple `(a, b, c)`. The
    * projection or tuple is kept so a fan-out branch can emit a matching
    * `.local`.
    */
  private def runArgAny(term: Term): Option[(Term, Term)] =
    term match {
      case Term.Apply.After_4_6_0(
            Term.Select(receiver, Term.Name("run" | "apply")),
            Term.ArgClause(List(arg), _)
          ) =>
        Some((receiver, arg))
      case Term.Apply.After_4_6_0(callee, Term.ArgClause(List(arg), _)) =>
        Some((callee, arg))
      case Term.Apply.After_4_6_0(callee, Term.ArgClause(args, _))
          if args.length > 1 =>
        Some((callee, Term.Tuple(args)))
      case _ =>
        None
    }

  /** The leftmost `Term.Name` an argument expression is rooted at -- `task` in
    * `task.run.worktreePath` -- whose symbol identifies which value (arrow
    * input or a prior binding) the step consumes. A tuple of auto-tupled
    * arguments has one root only when every element shares it.
    */
  private def argRoot(
      argExpr: Term
  )(implicit doc: SemanticDocument): Option[Symbol] =
    argExpr match {
      case name: Term.Name                => Some(name.symbol)
      case Term.Select(qual, _)           => argRoot(qual)
      case Term.Apply.After_4_6_0(fun, _) => argRoot(fun)
      case Term.Tuple(elements) =>
        val roots = elements.map(argRoot)
        roots.headOption.flatten.filter(root => roots.forall(_.contains(root)))
      case _ => None
    }

  private def singleFunction(applyTerm: Term.Apply): Option[Term.Function] =
    applyTerm.argClause.values match {
      case List(fn: Term.Function)                   => Some(fn)
      case List(Term.Block(List(fn: Term.Function))) => Some(fn)
      case _                                         => None
    }

  private def calleeIfKleisli(
      callee: Term
  )(implicit doc: SemanticDocument): Option[Term] =
    if (KleisliType.isKleisli(callee)) Some(callee) else None

  private def classify(
      steps: List[Step],
      yieldTerm: Term,
      inputSymbol: Symbol,
      input: Option[Input]
  )(implicit doc: SemanticDocument): Option[ArrowIR] =
    linearChain(steps, yieldTerm, inputSymbol)
      .orElse(fanOut(steps, yieldTerm, inputSymbol, input))

  /** Each step consumes the previous step's binding (the first consumes the
    * arrow input). The trailing value is one of: the last binding untouched (a
    * pure chain), a final Kleisli applied to the last binding (the desugared
    * `flatMap` form, whose last effect is inline rather than a generator), or a
    * pure function of the last binding (a chain with a `map` tail).
    */
  private def linearChain(
      steps: List[Step],
      yieldTerm: Term,
      inputSymbol: Symbol
  )(implicit doc: SemanticDocument): Option[ArrowIR] = {
    // A chain threads each step straight into the next; a projected argument
    // (`k.run(prev.field)`) would need a per-step `.local` and is not a pure
    // pipe, so linear only accepts bare-name arguments. Projections are handled
    // by the fan-out path.
    val bareArgs = steps.forall(_.argExpr.is[Term.Name])
    val threaded = steps.zip(inputSymbol :: steps.map(_.bindingSymbol)).forall {
      case (step, expectedArg) => step.argSymbol == expectedArg
    }
    if (!bareArgs || !threaded || steps.isEmpty) None
    else {
      val lastBinding = steps.last.bindingSymbol
      // Every intermediate binding must be consumed only as the next step's
      // argument. If one is used again -- in a later callee or in the reshape
      // -- the value cannot be dropped, so the chain is not a pure pipe.
      val nonLastBindings =
        steps.map(_.bindingSymbol).filterNot(_ == lastBinding)
      val forbidden = inputSymbol :: nonLastBindings
      chainTail(lastBinding, yieldTerm).flatMap { tail =>
        val captures = tail.reshape.exists(referencesAny(_, forbidden))
        if (captures) None
        else {
          val allEffects = steps.map(step => calleeIfKleisli(step.callee)) ++
            tail.finalEffect.map(calleeIfKleisli)
          traverse(allEffects)(identity).flatMap { callees =>
            if (callees.length < 2) None
            else {
              val composed =
                callees.map(Eff(_): ArrowIR).reduceLeft(AndThen(_, _))
              Some(tail.reshape.fold(composed)(Rmap(composed, _)))
            }
          }
        }
      }
    }
  }

  /** The tail of a chain: an optional final Kleisli effect and an optional pure
    * output reshape. Exactly one is populated, or neither (a bare binding).
    */
  private final case class ChainTail(
      finalEffect: Option[Term],
      reshape: Option[Term]
  )

  private def chainTail(
      lastBinding: Symbol,
      yieldTerm: Term
  )(implicit doc: SemanticDocument): Option[ChainTail] =
    yieldTerm match {
      case name: Term.Name if name.symbol == lastBinding =>
        Some(ChainTail(None, None))
      case _ =>
        // A final `k.run(lastBinding).map(fn)` -- the desugared chain's last
        // effect with a pure reshape.
        peelTrailingMap(yieldTerm) match {
          case Some((inner, fn)) =>
            runArgument(inner) match {
              case Some((callee, arg))
                  if arg.symbol == lastBinding &&
                    KleisliType.isKleisli(callee) =>
                Some(ChainTail(Some(callee), Some(fn)))
              case _ =>
                None
            }
          case None =>
            runArgument(yieldTerm) match {
              // A final `k.run(lastBinding)` is the desugared chain's last
              // effect.
              case Some((callee, arg))
                  if arg.symbol == lastBinding &&
                    KleisliType.isKleisli(callee) =>
                Some(ChainTail(Some(callee), None))
              case _ =>
                mapFunction(yieldTerm, lastBinding).map(fn =>
                  ChainTail(None, Some(fn))
                )
            }
        }
    }

  /** A `yield f(last)` expressed as the function `f`, when `f` mentions only
    * the last binding and is not itself a Kleisli application. Rendered later
    * as `.map(f)`.
    */
  private def mapFunction(yieldTerm: Term, lastBinding: Symbol)(implicit
      doc: SemanticDocument
  ): Option[Term] =
    yieldTerm match {
      case Term.Apply.After_4_6_0(fn, Term.ArgClause(List(arg: Term.Name), _))
          if arg.symbol == lastBinding && !KleisliType.isKleisli(fn) =>
        Some(fn)
      case _ =>
        None
    }

  /** All steps consume the same arrow input and never each other's bindings,
    * and the `yield` tuples the bindings in generator order: a fan-out. Folds
    * to `k1 &&& k2 &&& ...` at any arity, with a trailing `map` if the tuple is
    * reshaped.
    */
  private def fanOut(
      steps: List[Step],
      yieldTerm: Term,
      inputSymbol: Symbol,
      input: Option[Input]
  )(implicit doc: SemanticDocument): Option[ArrowIR] = {
    // Every branch reads the same arrow input, though possibly a different
    // projection of it (`k1.run(x.a)`, `k2.run(x.b)`). A projected branch
    // becomes `k.local(x => x.a)`; the readability budget counts each such
    // `.local` as plumbing, so a wide projected fan-out is declined rather than
    // turned into a wall of `.local`s.
    val sameInput =
      steps.length >= 2 && steps.forall(_.argSymbol == inputSymbol)
    val bindingSymbols = steps.map(_.bindingSymbol)
    val independent = steps.forall { step =>
      !referencesAny(step.callee, bindingSymbols)
    }
    if (!sameInput || !independent) None
    else
      for {
        branches <- traverse(steps)(step => branchArrow(step, input))
        reshape <- tupleReshape(yieldTerm, bindingSymbols)
      } yield {
        val merged = branches.reduceLeft(Merge(_, _))
        reshape.fold(merged)(Rmap(merged, _))
      }
  }

  /** One fan-out branch as an arrow: a bare `k.run(input)` is just `Eff(k)`; a
    * projected `k.run(input.field)` becomes
    * `Local(input => input.field, Eff(k))` i.e.
    * `k.local(input => input.field)`. The branch callee must be a Kleisli.
    */
  private def branchArrow(
      step: Step,
      input: Option[Input]
  )(implicit doc: SemanticDocument): Option[ArrowIR] =
    calleeIfKleisli(step.callee).flatMap { callee =>
      step.argExpr match {
        case _: Term.Name =>
          Some(Eff(callee))
        case projection =>
          // The projection lambda's parameter is annotated with the input type
          // -- `(request: Request) => request.path` -- because `Kleisli.local`
          // would otherwise infer the new input type as `Any`. Without the
          // input type in hand, a projected branch is not offered.
          input.map { in =>
            val projectionFn = Term.Function(
              Term.ParamClause(
                List(
                  Term.Param(
                    Nil,
                    Term.Name(in.name),
                    Some(Type.Name(in.tpe)),
                    None
                  )
                ),
                None
              ),
              projection
            )
            Local(projectionFn, Eff(callee))
          }
      }
    }

  /** `yield (a, b, c)` in generator order reshapes to nothing (`None`); any
    * other tuple or expression reshapes with a `map`. `&&&` nests its output as
    * `((a, b), c)`, so a flat `yield (a, b, c)` is *not* a no-op -- it needs a
    * `map` from the nested shape to the flat one, which is emitted here.
    */
  private def tupleReshape(
      yieldTerm: Term,
      bindingSymbols: List[Symbol]
  )(implicit doc: SemanticDocument): Option[Option[Term]] =
    yieldTerm match {
      case Term.Tuple(elements)
          if elements.length == bindingSymbols.length &&
            elements.zip(bindingSymbols).forall {
              case (name: Term.Name, sym) => name.symbol == sym
              case _                      => false
            } =>
        if (bindingSymbols.length == 2) Some(None)
        else Some(Some(flattenTupleFunction(bindingSymbols.length)))
      case _ =>
        None
    }

  /** A function reshaping the left-nested tuple `&&&` produces into the flat
    * tuple the source `yield` wrote, e.g. arity 3:
    * `{ case ((a, b), c) => (a, b, c) }`.
    */
  private def flattenTupleFunction(arity: Int): Term = {
    val names = (1 to arity).map(i => s"a$i").toList
    val nested = names.tail.foldLeft[Pat](Pat.Var(Term.Name(names.head))) {
      (acc, n) => Pat.Tuple(List(acc, Pat.Var(Term.Name(n))))
    }
    val flat = Term.Tuple(names.map(Term.Name(_)))
    Term.PartialFunction(
      List(Case(nested, None, flat))
    )
  }

  /** A fan-out near-miss: the body applies Kleislis to an argument *spelled*
    * like the arrow input more than once, but at least one of those spellings
    * resolves to a different binding -- an inner `val` has shadowed the input
    * before the second Kleisli runs, so the two are not fed the same value.
    * Returned so the rule can warn that the shape was seen and rejected, rather
    * than silently doing nothing. Position is the shadowed argument.
    */
  def shadowedInput(
      body: Term,
      inputName: String,
      inputSymbol: Symbol
  )(implicit doc: SemanticDocument): Option[scala.meta.inputs.Position] = {
    val spelledLikeInput = body.collect { case applyTerm: Term.Apply =>
      runArgument(applyTerm).collect {
        case (callee, arg)
            if arg.value == inputName && KleisliType.isKleisli(callee) =>
          arg
      }
    }.flatten
    val hasRealInput = spelledLikeInput.exists(_.symbol == inputSymbol)
    val shadowed = spelledLikeInput.find(_.symbol != inputSymbol)
    if (hasRealInput && spelledLikeInput.length >= 2) shadowed.map(_.pos)
    else None
  }

  private def referencesAny(term: Term, symbols: List[Symbol])(implicit
      doc: SemanticDocument
  ): Boolean =
    term.collect {
      case name: Term.Name if symbols.contains(name.symbol) => ()
    }.nonEmpty

  private def traverse[A, B](xs: List[A])(f: A => Option[B]): Option[List[B]] =
    xs.foldRight(Option(List.empty[B])) { (a, acc) =>
      for {
        b <- f(a)
        bs <- acc
      } yield b :: bs
    }
}
