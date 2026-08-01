package fix

import scala.meta._

import scalafix.v1._

import fix.catsexpr.CatsFacts

final class PreferCatsSyntax extends SemanticRule("PreferCatsSyntax") {
  override def fix(implicit doc: SemanticDocument): Patch =
    CatsExpressionRules
      .preferCatsSyntaxRewrites(doc.tree, CatsFacts.semantic)
      .map { rewrite =>
        Patch.replaceTree(rewrite.tree, rewrite.replacement) +
          CatsExpressionRules.catsSyntaxImport
      }
      .asPatch
}

final class SimplifyCatsExpressions
    extends SemanticRule("SimplifyCatsExpressions") {
  override def fix(implicit doc: SemanticDocument): Patch =
    CatsExpressionRules
      .simplifyExpressionRewrites(doc.tree, CatsFacts.semantic)
      .map { rewrite =>
        Patch.replaceTree(rewrite.tree, rewrite.replacement) +
          CatsExpressionRules.catsSyntaxImport
      }
      .asPatch
}

object PreferCatsSyntax {
  def rewrites(tree: Tree, facts: CatsFacts): List[String] =
    CatsExpressionRules.preferCatsSyntaxRewrites(tree, facts).map(_.replacement)
}

object SimplifyCatsExpressions {
  def rewrites(tree: Tree, facts: CatsFacts): List[String] =
    CatsExpressionRules
      .simplifyExpressionRewrites(tree, facts)
      .map(_.replacement)
}

/** Rewrites toward Cats syntax and Cats combinators.
  *
  * Every matcher here decides through [[fix.catsexpr.CatsFacts]], never through
  * an identifier's spelling. `map` on a `List` is not `map` on an
  * `F[_]: Functor` even though both are spelled the same, and `Right` is only
  * `scala.util.Right` until someone shadows it. A rewrite that cannot resolve
  * the symbols it depends on does not fire.
  */
private[fix] object CatsExpressionRules {
  final case class Rewrite(tree: Tree, replacement: String)

  /** `Patch.addGlobalImport(Symbol("cats/syntax/all."))` renders as `import
    * cats.syntax.all` -- the object itself, not its members -- which brings no
    * syntax into scope and leaves the rewritten file uncompilable. The importer
    * form emits the wildcard the rewrites actually need.
    */
  private val catsSyntaxAll: Importer =
    Importer(
      Term.Select(
        Term.Select(Term.Name("cats"), Term.Name("syntax")),
        Term.Name("all")
      ),
      List(Importee.Wildcard())
    )

  def catsSyntaxImport(implicit doc: SemanticDocument): Patch =
    if (importsCatsSyntax(doc.tree)) Patch.empty
    else Patch.addGlobalImport(catsSyntaxAll)

  /** Scalafix dedupes a global import against the symbol it resolves, so an
    * `import cats.syntax.all.*` already in the file does not stop it adding a
    * second wildcard importer. Check for one directly.
    */
  private def importsCatsSyntax(tree: Tree): Boolean =
    tree.collect {
      case Importer(ref, importees)
          if CatsSyntaxWildcardRefs(ref.syntax) &&
            importees.exists(_.is[Importee.Wildcard]) =>
        ()
    }.nonEmpty

  private val CatsSyntaxWildcardRefs: Set[String] =
    Set("cats.syntax.all", "cats.implicits")

  def preferCatsSyntaxRewrites(tree: Tree, facts: CatsFacts): List[Rewrite] =
    tree.collect { case term: Term =>
      preferCatsSyntaxRewrite(term, facts)
    }.flatten

  def simplifyExpressionRewrites(tree: Tree, facts: CatsFacts): List[Rewrite] =
    tree.collect { case term: Term =>
      simplifyExpressionRewrite(term, facts)
    }.flatten

  def preferCatsSyntaxRewrite(
      term: Term,
      facts: CatsFacts
  ): Option[Rewrite] =
    pureSyntax(term, facts)
      .orElse(raiseErrorSyntax(term, facts))
      .orElse(mapSyntax(term, facts))
      .orElse(flatMapSyntax(term, facts))

  def simplifyExpressionRewrite(
      term: Term,
      facts: CatsFacts
  ): Option[Rewrite] =
    voidSyntax(term, facts)
      .orElse(asSyntax(term, facts))
      .orElse(flatMapPureSyntax(term, facts))
      .orElse(sequenceSyntax(term, facts))
      .orElse(mapNSyntax(term, facts))
      .orElse(optionSyntax(term, facts))
      .orElse(eitherCondSyntax(term, facts))

  private def pureSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(receiver, name) if name.value == "pure" =>
            for {
              effectType <- typeclassEffect(
                receiver,
                CatsFacts.Typeclasses.pure,
                facts
              )
              value <- singleArg(apply.argClause.values)
            } yield Rewrite(
              term,
              s"${value.syntax}.pure[${effectType.syntax}]"
            )
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def raiseErrorSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case applyType: Term.ApplyType =>
            applyType.fun match {
              case Term.Select(receiver, name) if name.value == "raiseError" =>
                for {
                  effectType <- typeclassEffect(
                    receiver,
                    CatsFacts.Typeclasses.raiseError,
                    facts
                  )
                  resultType <- singleTypeArg(applyType.targClause.values)
                  error <- singleArg(apply.argClause.values)
                } yield Rewrite(
                  term,
                  s"${error.syntax}.raiseError[${effectType.syntax}, ${resultType.syntax}]"
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

  private def mapSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    typeclassCombinator(term, "map", CatsFacts.Typeclasses.map, facts)

  private def flatMapSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    typeclassCombinator(term, "flatMap", CatsFacts.Typeclasses.flatMap, facts)

  /** `Typeclass[F].method(fa)(f)` -> `fa.method(f)`. */
  private def typeclassCombinator(
      term: Term,
      method: String,
      allowed: Set[String],
      facts: CatsFacts
  ): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case receiverApply: Term.Apply =>
            receiverApply.fun match {
              case Term.Select(receiver, name) if name.value == method =>
                for {
                  _ <- typeclassEffect(receiver, allowed, facts)
                  effect <- singleArg(receiverApply.argClause.values)
                  function <- singleArg(apply.argClause.values)
                } yield Rewrite(
                  term,
                  s"${effect.syntax}.$method(${function.syntax})"
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

  private def voidSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case select @ Term.Select(effect, Term.Name("as"))
              if facts.isCatsOperation(select) &&
                singleArg(apply.argClause.values).contains(Lit.Unit()) =>
            Some(Rewrite(term, s"${effect.syntax}.void"))
          case select @ Term.Select(effect, Term.Name("map"))
              if facts.isCatsOperation(select) &&
                singleArg(apply.argClause.values).exists(UnitLambda.unapply) =>
            Some(Rewrite(term, s"${effect.syntax}.void"))
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def asSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case select @ Term.Select(effect, Term.Name("map"))
              if facts.isCatsOperation(select) =>
            singleArg(apply.argClause.values)
              .flatMap(ConstantLambda.unapply)
              .map { value =>
                Rewrite(term, s"${effect.syntax}.as(${value.syntax})")
              }
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def flatMapPureSyntax(
      term: Term,
      facts: CatsFacts
  ): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case select @ Term.Select(effect, Term.Name("flatMap"))
              if facts.isCatsOperation(select) =>
            singleArg(apply.argClause.values)
              .collect { case lambda: Term.Function =>
                lambda
              }
              .flatMap { lambda =>
                for {
                  param <- singleParam(lambda)
                  (body, _) <- pureBody(lambda.body, facts)
                  if namedParam(param).exists(name => references(body, name))
                } yield Rewrite(
                  term,
                  s"${effect.syntax}.map(${lambdaParamSyntax(lambda.paramClause)} => ${body.syntax})"
                )
              }
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def sequenceSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case select @ Term.Select(effect, Term.Name("flatMap"))
              if facts.isCatsOperation(select) =>
            singleArg(apply.argClause.values)
              .flatMap(ConstantLambda.unapply)
              .map { next =>
                Rewrite(term, s"${effect.syntax} *> ${next.syntax}")
              }
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def mapNSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case outer @ Term.Select(firstEffect, Term.Name("flatMap"))
              if facts.isCatsOperation(outer) =>
            for {
              firstFunction <- singleArg(apply.argClause.values).collect {
                case function: Term.Function => function
              }
              firstParam <- singleParam(firstFunction)
              firstName <- namedParam(firstParam)
              secondApply <- Some(firstFunction.body).collect {
                case apply: Term.Apply => apply
              }
              secondEffect <- Some(secondApply.fun).collect {
                case inner @ Term.Select(effect, Term.Name("map"))
                    if facts.isCatsOperation(inner) =>
                  effect
              }
              if !references(secondEffect, firstName)
              secondFunction <- singleArg(secondApply.argClause.values)
                .collect { case function: Term.Function =>
                  function
                }
            } yield {
              Rewrite(
                term,
                s"(${firstEffect.syntax}, ${secondEffect.syntax}).mapN(${mapNFunction(firstParam, secondFunction)})"
              )
            }
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def optionSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case Term.If.After_4_4_0(
            NullComparison(value, "=="),
            none,
            someApply: Term.Apply,
            _
          ) if isNone(none, facts) =>
        someValue(someApply, facts)
          .filter(some => sameSyntax(value, some))
          .map(_ => Rewrite(term, s"Option(${value.syntax})"))
      case Term.If.After_4_4_0(
            NullComparison(value, "!="),
            someApply: Term.Apply,
            none,
            _
          ) if isNone(none, facts) =>
        someValue(someApply, facts)
          .filter(some => sameSyntax(value, some))
          .map(_ => Rewrite(term, s"Option(${value.syntax})"))
      case _ =>
        None
    }

  private def eitherCondSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case Term.If.After_4_4_0(condition, thenBranch, elseBranch, _) =>
        (
          constructorArg(thenBranch, CatsFacts.Constructors.right, facts),
          constructorArg(elseBranch, CatsFacts.Constructors.left, facts)
        ) match {
          case (Some(right), Some(left)) =>
            Some(
              Rewrite(
                term,
                s"Either.cond(${condition.syntax}, ${right.syntax}, ${left.syntax})"
              )
            )
          case _ =>
            (
              constructorArg(thenBranch, CatsFacts.Constructors.left, facts),
              constructorArg(elseBranch, CatsFacts.Constructors.right, facts)
            ) match {
              case (Some(left), Some(right)) =>
                Some(
                  Rewrite(
                    term,
                    s"Either.cond(!(${condition.syntax}), ${right.syntax}, ${left.syntax})"
                  )
                )
              case _ =>
                None
            }
        }
      case _ =>
        None
    }

  /** The effect type of a `Typeclass[F]` receiver, when `Typeclass` resolves to
    * one of `allowed`.
    */
  private def typeclassEffect(
      receiver: Term,
      allowed: Set[String],
      facts: CatsFacts
  ): Option[Type] =
    receiver match {
      case applyType: Term.ApplyType =>
        facts
          .typeclassObject(applyType.fun)
          .filter(allowed)
          .flatMap(_ => singleTypeArg(applyType.targClause.values))
      case _ =>
        None
    }

  private object UnitLambda {
    def unapply(term: Term): Boolean =
      term match {
        case function: Term.Function =>
          function.paramClause.values.length == 1 && function.body.is[Lit.Unit]
        case _ =>
          false
      }
  }

  private object ConstantLambda {
    def unapply(term: Term): Option[Term] =
      term match {
        case function: Term.Function
            if singleParam(function).forall(param =>
              namedParam(param).forall(name => !references(function.body, name))
            ) &&
              !function.body.is[Lit.Unit] =>
          Some(function.body)
        case _ =>
          None
      }
  }

  /** `x.pure[F]` or `Typeclass[F].pure(x)`, with the lifted value and effect.
    */
  private def pureBody(
      term: Term,
      facts: CatsFacts
  ): Option[(Term, Type)] =
    term match {
      case applyType: Term.ApplyType =>
        applyType.fun match {
          case Term.Select(body, Term.Name("pure")) =>
            singleTypeArg(applyType.targClause.values).map(effectType =>
              body -> effectType
            )
          case _ =>
            None
        }
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(receiver, name) if name.value == "pure" =>
            for {
              effectType <- typeclassEffect(
                receiver,
                CatsFacts.Typeclasses.pure,
                facts
              )
              body <- singleArg(apply.argClause.values)
            } yield body -> effectType
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def namedParam(param: Term.Param): Option[String] =
    param.name match {
      case name: Name if name.value != "_" => Some(name.value)
      case _                               => None
    }

  /** Whether `name` occurs anywhere in `term`, ignoring shadowing.
    *
    * Every call site uses this to *suppress* a rewrite: a lambda counts as
    * constant only when its parameter is unreferenced, and `mapN` only applies
    * when the second effect does not depend on the first bound value. In that
    * direction over-approximating is safe -- a shadowed occurrence merely stops
    * a rewrite that would have been valid. Under-approximating would not be, so
    * do not narrow this without re-checking each caller.
    */
  private def references(term: Term, name: String): Boolean =
    term.collect { case Term.Name(`name`) => () }.nonEmpty

  private def singleArg(args: List[Term]): Option[Term] =
    args match {
      case List(arg) => Some(arg)
      case _         => None
    }

  private def singleTypeArg(args: List[Type]): Option[Type] =
    args match {
      case List(arg) => Some(arg)
      case _         => None
    }

  private def singleParam(function: Term.Function): Option[Term.Param] =
    function.paramClause.values match {
      case List(param) => Some(param)
      case _           => None
    }

  private def mapNFunction(
      firstParam: Term.Param,
      secondFunction: Term.Function
  ): String =
    secondFunction.paramClause.values match {
      case List(secondParam) =>
        s"(${firstParam.syntax}, ${secondParam.syntax}) => ${secondFunction.body.syntax}"
      case _ =>
        secondFunction.syntax
    }

  private def lambdaParamSyntax(params: Term.ParamClause): String =
    lambdaParamSyntax(params.values)

  private def lambdaParamSyntax(params: List[Term.Param]): String =
    params match {
      case List(param) => param.syntax
      case many        => many.map(_.syntax).mkString("(", ", ", ")")
    }

  private def sameSyntax(left: Term, right: Term): Boolean =
    left.syntax == right.syntax

  private object NullComparison {
    def unapply(term: Term): Option[(Term, String)] =
      term match {
        case infix: Term.ApplyInfix
            if (infix.op.value == "==" || infix.op.value == "!=") &&
              singleArg(infix.argClause.values).exists(_.is[Lit.Null]) =>
          Some(infix.lhs -> infix.op.value)
        case _ =>
          None
      }
  }

  private def someValue(apply: Term.Apply, facts: CatsFacts): Option[Term] =
    constructorArg(apply, CatsFacts.Constructors.some, facts)

  /** The single argument of `Constructor(arg)`, when `Constructor` resolves to
    * one of `symbols`.
    */
  private def constructorArg(
      term: Term,
      symbols: Set[String],
      facts: CatsFacts
  ): Option[Term] =
    term match {
      case apply: Term.Apply if facts.resolvesTo(apply.fun, symbols) =>
        singleArg(apply.argClause.values)
      case _ =>
        None
    }

  private def isNone(term: Term, facts: CatsFacts): Boolean =
    facts.resolvesTo(term, CatsFacts.Constructors.none)
}
