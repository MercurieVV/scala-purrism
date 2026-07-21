package fix

import scala.meta._

import scalafix.v1._

/** A Pattern C candidate where both `.run`/`.apply` arguments share the
  * def's own parameter *name* but not its *symbol* -- the shape docs/RULES.md
  * warns about: an inner scope shadows the input before the second Kleisli
  * runs, so the two calls are not actually fed the same value. Reported
  * rather than silently skipped so a reader is warned the near-miss was
  * seen and rejected, not simply never recognised.
  *
  * `Diagnostic` defaults to `LintSeverity.Error`, and scalafix withholds a
  * rule's patches for a file that reports lint errors, so this stays a
  * `Warning` -- otherwise this diagnostic would blank out any real patch the
  * same rule run also produced elsewhere in the file.
  */
final case class FanOutShadowedInputDiagnostic(
    override val position: scala.meta.inputs.Position
) extends Diagnostic {
  override def message: String =
    "Both branches call .run/.apply with an argument spelled like this def's " +
      "parameter, but the second one resolves to a different binding " +
      "(likely shadowed in an inner scope). Not rewriting to `&&&`, since " +
      "the two Kleislis would no longer run on the same input."
  override def severity: scalafix.lint.LintSeverity =
    scalafix.lint.LintSeverity.Warning
}

/** Prefer point-free `Arrow` composition over unpacking a `Kleisli` with
  * `.run`/`.apply` and stitching the pieces back together by hand.
  *
  * Three shapes are handled:
  *
  *   - Pattern A: a linear chain of three or more `flatMap` steps (or the
  *     equivalent `for`-comprehension), each one calling `.run`/`.apply` on
  *     the previous step's result and threading it straight into the next
  *     Kleisli, collapses to `k1.andThen(k2).andThen(k3)`.
  *   - Pattern B: `k.run(x).map(f)` where `x` is the def's own input and `f`
  *     does not close over it collapses to `k.map(f)`.
  *   - Pattern C: two Kleislis run on the *same* input and paired into a
  *     tuple (`for { b <- k1.run(x); c <- k2.run(x) } yield (b, c)`, or its
  *     `flatMap`/`map` desugaring) collapses to `k1 &&& k2`. Unlike A and B,
  *     "same input" is a binding-identity question, not a spelling one, so
  *     it is resolved via SemanticDB symbols (see [[fanOutCandidate]] /
  *     [[fanOutRewrite]]), never by comparing `Term.Name` strings.
  *
  * All three rewrites turn `def m(x: A): F[B]` into `def m: Kleisli[F, A, B]`,
  * so they only fire when the def takes exactly one plain parameter and every
  * step resolves, via [[PreferKleisli.collectKleisliNames]], to a receiver
  * actually declared as a `Kleisli` -- syntactic shape alone (an unrelated
  * type that also happens to expose `.run`) is not enough.
  */
final class PreferArrow extends SemanticRule("PreferArrow") {
  override def fix(implicit doc: SemanticDocument): Patch = {
    val knownKleislies = PreferKleisli.collectKleisliNames(doc.tree)

    PreferKleisli
      .rewriteCandidates(doc.tree)
      .filter(PreferArrow.isSinglePlainParameterCandidate)
      .map(PreferArrow.rewrite(_, knownKleislies))
      .asPatch
  }
}

object PreferArrow {
  private def isSinglePlainParameterCandidate(defn: Defn.Def): Boolean =
    defn.decltpe.exists(_.is[Type.Apply]) &&
      singlePlainParameter(defn).isDefined

  private def singlePlainParameter(defn: Defn.Def): Option[Term.Param] =
    defn.paramClauseGroups match {
      case List(group)
          if group.tparamClause.values.isEmpty &&
            group.paramClauses.length == 1 &&
            group.paramClauses.head.mod.isEmpty =>
        group.paramClauses.head.values match {
          case List(param)
              if param.mods.isEmpty && param.default.isEmpty &&
                param.decltpe.exists(!_.is[Type.Repeated]) =>
            Some(param)
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def isFResult(returnType: Type.Apply): Boolean =
    returnType.tpe.syntax == "F" && returnType.argClause.values.length == 1

  private def renderModifiers(mods: List[Mod]): String =
    mods.map(_.syntax + " ").mkString

  private def references(term: Term, name: String): Boolean =
    term.collect { case Term.Name(`name`) => () }.nonEmpty

  /** The receiver of a single-argument `.run(x)`/`.apply(x)` call (or a bare
    * reference to a known Kleisli), gated on the receiver actually being a
    * declared `Kleisli` -- [[PreferKleisli.kleisliCallee]] itself only checks
    * the method name, so callers must additionally require membership in
    * `knownKleislies`.
    */
  private def stepCallee(
      term: Term,
      expectedArgument: String,
      knownKleislies: Set[String]
  ): Option[String] =
    term match {
      case applyTerm: Term.Apply =>
        applyTerm.argClause.values match {
          case List(Term.Name(name)) if name == expectedArgument =>
            PreferKleisli
              .kleisliCallee(applyTerm.fun, knownKleislies)
              .filter(knownKleislies.contains)
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def singleFunctionArg(
      applyTerm: Term.Apply
  ): Option[Term.Function] =
    applyTerm.argClause.values match {
      case List(function: Term.Function) => Some(function)
      case List(Term.Block(List(function: Term.Function))) => Some(function)
      case _ => None
    }

  private def singleFunctionParamName(
      function: Term.Function
  ): Option[String] =
    function.paramClause.values match {
      case List(param) =>
        param.name match {
          case name: Name if name.value != "_" => Some(name.value)
          case _                                => None
        }
      case _ =>
        None
    }

  /** Walks a nested `flatMap` chain (or its terminal call) collecting the
    * Kleisli name at each step, requiring every step to thread the previous
    * step's bound value straight into the next call with nothing held back.
    */
  private def collectChainSteps(
      term: Term,
      expectedArgument: String,
      knownKleislies: Set[String]
  ): Option[List[String]] =
    term match {
      case applyTerm: Term.Apply =>
        applyTerm.fun match {
          case Term.Select(firstCall, Term.Name("flatMap")) =>
            for {
              name <- stepCallee(firstCall, expectedArgument, knownKleislies)
              function <- singleFunctionArg(applyTerm)
              nextArgument <- singleFunctionParamName(function)
              rest <- collectChainSteps(
                function.body,
                nextArgument,
                knownKleislies
              )
            } yield name :: rest
          case _ =>
            stepCallee(term, expectedArgument, knownKleislies).map(List(_))
        }
      case _ =>
        None
    }

  private def collectForSteps(
      enumerators: List[Enumerator],
      yieldBody: Term,
      expectedArgument: String,
      knownKleislies: Set[String]
  ): Option[List[String]] =
    enumerators match {
      case Enumerator.Generator(Pat.Var(boundNameTerm), rhs) :: rest =>
        val boundName = boundNameTerm.value
        for {
          name <- stepCallee(rhs, expectedArgument, knownKleislies)
          restSteps <-
            if (rest.isEmpty)
              yieldBody match {
                case Term.Name(name) if name == boundName => Some(Nil)
                case _                                     => None
              }
            else
              collectForSteps(rest, yieldBody, boundName, knownKleislies)
        } yield name :: restSteps
      case _ =>
        None
    }

  private def chainSteps(
      body: Term,
      inputArgument: String,
      knownKleislies: Set[String]
  ): Option[List[String]] =
    body match {
      case Term.ForYield.After_4_9_9(enumerators, yieldBody) =>
        collectForSteps(enumerators, yieldBody, inputArgument, knownKleislies)
      case applyTerm: Term.Apply
          if applyTerm.fun match {
            case Term.Select(_, Term.Name("flatMap")) => true
            case _                                    => false
          } =>
        collectChainSteps(applyTerm, inputArgument, knownKleislies)
      case _ =>
        None
    }

  private def chainRewrite(
      defn: Defn.Def,
      param: Term.Param,
      returnType: Type.Apply,
      knownKleislies: Set[String]
  ): Option[String] = {
    val paramName = param.name
    chainSteps(defn.body, paramName.value, knownKleislies)
      .filter(_.length >= 3)
      .map { steps =>
        val modifiers = renderModifiers(defn.mods)
        val parameterType = param.decltpe.map(_.syntax).getOrElse("")
        val result = returnType.argClause.values.head
        val expression =
          steps.tail.foldLeft(steps.head) { (acc, next) =>
            s"$acc.andThen($next)"
          }

        s"""${modifiers}def ${defn.name.syntax}: Kleisli[${returnType.tpe.syntax}, $parameterType, ${result.syntax}] =
           |  $expression""".stripMargin
      }
  }

  private def mapRewrite(
      defn: Defn.Def,
      param: Term.Param,
      returnType: Type.Apply,
      knownKleislies: Set[String]
  ): Option[String] = {
    val paramName = param.name
    defn.body match {
      case applyTerm: Term.Apply =>
        applyTerm.fun match {
          case Term.Select(runCall, Term.Name("map")) =>
            (
              stepCallee(runCall, paramName.value, knownKleislies),
              applyTerm.argClause.values
            ) match {
              case (Some(callee), List(mapArg))
                  if !references(mapArg, paramName.value) =>
                val modifiers = renderModifiers(defn.mods)
                val parameterType =
                  param.decltpe.map(_.syntax).getOrElse("")
                val result = returnType.argClause.values.head

                Some(
                  s"""${modifiers}def ${defn.name.syntax}: Kleisli[${returnType.tpe.syntax}, $parameterType, ${result.syntax}] =
                     |  $callee.map(${mapArg.syntax})""".stripMargin
                )
              case _ =>
                None
            }
          case _ =>
            None
        }
      case _ =>
        None
    }
  }

  /** The two steps of a candidate Pattern C fan-out, before binding identity
    * or Kleisli-ness has been checked. `firstCalleeExpr` / `secondCalleeExpr`
    * are the expressions receiving `.run`/`.apply` (e.g. `loadUser`);
    * `firstArgument` / `secondArgument` are the `Term.Name`s passed as the
    * argument to each call, whose *symbols* -- not spellings -- decide
    * whether this is really one input threaded twice.
    */
  private final case class FanOutCandidate(
      firstCalleeExpr: Term,
      firstArgument: Term.Name,
      firstBoundName: String,
      secondCalleeExpr: Term,
      secondArgument: Term.Name
  )

  private def runArgument(term: Term): Option[(Term, Term.Name)] =
    term match {
      case applyTerm: Term.Apply =>
        applyTerm.argClause.values match {
          case List(name: Term.Name) => Some((applyTerm.fun, name))
          case _                     => None
        }
      case _ =>
        None
    }

  private def forYieldFanOut(body: Term): Option[FanOutCandidate] =
    body match {
      // The enumerators are a `Term.EnumeratorsBlock`, not a bare `List`, so
      // its `.enums` must be matched explicitly -- a pattern can't rely on
      // the implicit `List[Enumerator]` conversion the way an expression can.
      case Term.ForYield.After_4_9_9(enumeratorsBlock, yieldBody) =>
        enumeratorsBlock.enums match {
          case List(
                Enumerator.Generator(Pat.Var(firstBound), firstRun),
                Enumerator.Generator(Pat.Var(secondBound), secondRun)
              ) =>
            yieldBody match {
              case Term.Tuple(List(Term.Name(t1), Term.Name(t2)))
                  if t1 == firstBound.value && t2 == secondBound.value =>
                for {
                  first <- runArgument(firstRun)
                  second <- runArgument(secondRun)
                } yield FanOutCandidate(
                  first._1,
                  first._2,
                  firstBound.value,
                  second._1,
                  second._2
                )
              case _ =>
                None
            }
          case _ =>
            None
        }
      case _ =>
        None
    }

  /** The last expression of a function body, unwrapping a `Term.Block` --
    * present when the body shadows a binding with a `val` before the tail
    * expression, as in the Pattern C negative fixture. Unwrapping only
    * changes which syntax shape is *recognised*; it is
    * [[sameBinding]]/symbol resolution, not this unwrapping, that decides
    * whether a shadowed name still counts as the same input.
    */
  private def lastExpr(term: Term): Option[Term] =
    term match {
      case Term.Block(stats) =>
        stats.lastOption.collect { case last: Term => last }
      case other =>
        Some(other)
    }

  /** The desugared spine of the same shape:
    * `k1.run(x).flatMap(b => k2.run(x).map(c => (b, c)))`.
    */
  private def desugaredFanOut(body: Term): Option[FanOutCandidate] =
    body match {
      case applyTerm: Term.Apply =>
        applyTerm.fun match {
          case Term.Select(firstRun, Term.Name("flatMap")) =>
            for {
              outerFn <- singleFunctionArg(applyTerm)
              outerParamName <- singleFunctionParamName(outerFn)
              lastOuterStat <- lastExpr(outerFn.body)
              innerApply <- lastOuterStat match {
                case inner: Term.Apply => Some(inner)
                case _                 => None
              }
              secondRun <- innerApply.fun match {
                case Term.Select(secondRunTerm, Term.Name("map")) =>
                  Some(secondRunTerm)
                case _ =>
                  None
              }
              innerFn <- singleFunctionArg(innerApply)
              innerParamName <- singleFunctionParamName(innerFn)
              _ <- innerFn.body match {
                case Term.Tuple(List(Term.Name(t1), Term.Name(t2)))
                    if t1 == outerParamName && t2 == innerParamName =>
                  Some(())
                case _ =>
                  None
              }
              first <- runArgument(firstRun)
              second <- runArgument(secondRun)
            } yield FanOutCandidate(
              first._1,
              first._2,
              outerParamName,
              second._1,
              second._2
            )
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def fanOutCandidate(body: Term): Option[FanOutCandidate] =
    forYieldFanOut(body).orElse(desugaredFanOut(body))

  /** `import cats.syntax.arrow._`, built as an AST rather than via the
    * `importer"..."` quasiquote macro, which Scala 2 macros -- and this
    * project targets Scala 3 -- cannot run.
    */
  private val ArrowSyntaxImporter: Importer =
    Importer(
      Term.Select(
        Term.Select(Term.Name("cats"), Term.Name("syntax")),
        Term.Name("arrow")
      ),
      List(Importee.Wildcard())
    )

  private val MonadOrStrongerBounds =
    Set("Monad", "Async", "Concurrent", "MonadCancel", "MonadThrow", "Sync", "Temporal")

  private def contextBoundNames(tparam: Type.Param): List[String] =
    tparam.bounds.context.collect {
      case Type.Name(name)                          => name
      case Type.Select(_, Type.Name(name))           => name
      case applyTerm: Type.Apply if applyTerm.tpe.is[Type.Name] =>
        applyTerm.tpe.asInstanceOf[Type.Name].value
    }

  private def tparamsOf(tree: Tree): List[Type.Param] =
    tree match {
      case cls: Defn.Class => cls.tparamClause.values
      case trt: Defn.Trait => trt.tparamClause.values
      case defn: Defn.Def =>
        defn.paramClauseGroups.flatMap(_.tparamClause.values)
      case _ => Nil
    }

  /** `Kleisli`'s `ArrowChoice`/`Arrow` instance (`&&&`) requires `Monad[F]`,
    * strictly stronger than the `FlatMap[F]` the original `for`-comprehension
    * needs -- see the effect-ordering note on issue #8. Walk up from the
    * `def` to find whichever enclosing class/trait/def declares the effect
    * type parameter and check its context bound is `Monad` or a subtype.
    */
  private def hasMonadOrStrongerBound(
      defn: Defn.Def,
      effectTypeName: String
  ): Boolean = {
    def search(tree: Tree): Boolean = {
      val here = tparamsOf(tree).exists { tparam =>
        tparam.name.value == effectTypeName &&
          contextBoundNames(tparam).exists(MonadOrStrongerBounds.contains)
      }
      here || tree.parent.exists(search)
    }
    search(defn)
  }

  private def sameBinding(
      argument: Term.Name,
      param: Term.Param
  )(implicit doc: SemanticDocument): Boolean = {
    val paramSymbol = param.name.symbol
    paramSymbol != Symbol.None && argument.symbol == paramSymbol
  }

  private def fanOutRewrite(
      defn: Defn.Def,
      param: Term.Param,
      returnType: Type.Apply,
      knownKleislies: Set[String]
  )(implicit doc: SemanticDocument): Option[String] =
    for {
      candidate <- fanOutCandidate(defn.body)
      if sameBinding(candidate.firstArgument, param) &&
        sameBinding(candidate.secondArgument, param)
      if !references(candidate.secondCalleeExpr, candidate.firstBoundName)
      if hasMonadOrStrongerBound(defn, returnType.tpe.syntax)
      firstCallee <- PreferKleisli
        .kleisliCallee(candidate.firstCalleeExpr, knownKleislies)
        .filter(knownKleislies.contains)
      secondCallee <- PreferKleisli
        .kleisliCallee(candidate.secondCalleeExpr, knownKleislies)
        .filter(knownKleislies.contains)
    } yield {
      val modifiers = renderModifiers(defn.mods)
      val parameterType = param.decltpe.map(_.syntax).getOrElse("")
      val result = returnType.argClause.values.head

      s"""${modifiers}def ${defn.name.syntax}: Kleisli[${returnType.tpe.syntax}, $parameterType, ${result.syntax}] =
         |  $firstCallee &&& $secondCallee""".stripMargin
    }

  private def fanOutShadowDiagnostic(
      defn: Defn.Def,
      param: Term.Param
  )(implicit doc: SemanticDocument): Option[Patch] =
    fanOutCandidate(defn.body).collect {
      case candidate
          if candidate.firstArgument.value == param.name.value &&
            candidate.secondArgument.value == param.name.value &&
            !(sameBinding(candidate.firstArgument, param) &&
              sameBinding(candidate.secondArgument, param)) =>
        Patch.lint(FanOutShadowedInputDiagnostic(candidate.secondArgument.pos))
    }

  def rewrite(
      defn: Defn.Def,
      knownKleislies: Set[String]
  )(implicit doc: SemanticDocument): Patch =
    (for {
      param <- singlePlainParameter(defn)
      returnType <- defn.decltpe.collect { case tpe: Type.Apply => tpe }
      if isFResult(returnType)
    } yield (param, returnType)) match {
      case None =>
        Patch.empty
      case Some((param, returnType)) =>
        chainRewrite(defn, param, returnType, knownKleislies) match {
          case Some(rewritten) =>
            Patch.addGlobalImport(Symbol("cats/data/Kleisli#")) +
              Patch.replaceTree(defn, rewritten)
          case None =>
            mapRewrite(defn, param, returnType, knownKleislies) match {
              case Some(rewritten) =>
                Patch.addGlobalImport(Symbol("cats/data/Kleisli#")) +
                  Patch.replaceTree(defn, rewritten)
              case None =>
                fanOutRewrite(defn, param, returnType, knownKleislies) match {
                  case Some(rewritten) =>
                    Patch.addGlobalImport(Symbol("cats/data/Kleisli#")) +
                      Patch.addGlobalImport(ArrowSyntaxImporter) +
                      Patch.replaceTree(defn, rewritten)
                  case None =>
                    fanOutShadowDiagnostic(defn, param).getOrElse(Patch.empty)
                }
            }
        }
    }
}
