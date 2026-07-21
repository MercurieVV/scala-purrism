package fix

import scala.meta._

import scalafix.v1._

/** Prefer point-free `Arrow` composition over unpacking a `Kleisli` with
  * `.run`/`.apply` and stitching the pieces back together by hand.
  *
  * Two shapes are handled:
  *
  *   - Pattern A: a linear chain of three or more `flatMap` steps (or the
  *     equivalent `for`-comprehension), each one calling `.run`/`.apply` on
  *     the previous step's result and threading it straight into the next
  *     Kleisli, collapses to `k1.andThen(k2).andThen(k3)`.
  *   - Pattern B: `k.run(x).map(f)` where `x` is the def's own input and `f`
  *     does not close over it collapses to `k.map(f)`.
  *
  * Both rewrites turn `def m(x: A): F[B]` into `def m: Kleisli[F, A, B]`, so
  * they only fire when the def takes exactly one plain parameter and every
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
                Patch.empty
            }
        }
    }
}
