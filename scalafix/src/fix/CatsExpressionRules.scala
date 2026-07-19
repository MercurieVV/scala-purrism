package fix

import scala.annotation.nowarn
import scala.meta._

import scalafix.v1._

final class PreferCatsSyntax extends SemanticRule("PreferCatsSyntax") {
  override def fix(implicit doc: SemanticDocument): Patch =
    CatsExpressionRules
      .preferCatsSyntaxRewrites(doc.tree)
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
      .simplifyExpressionRewrites(doc.tree)
      .map { rewrite =>
        Patch.replaceTree(rewrite.tree, rewrite.replacement) +
          CatsExpressionRules.catsSyntaxImport
      }
      .asPatch
}

object PreferCatsSyntax {
  def rewrites(tree: Tree): List[String] =
    CatsExpressionRules.preferCatsSyntaxRewrites(tree).map(_.replacement)
}

object SimplifyCatsExpressions {
  def rewrites(tree: Tree): List[String] =
    CatsExpressionRules.simplifyExpressionRewrites(tree).map(_.replacement)
}

@nowarn
private[fix] object CatsExpressionRules {
  final case class Rewrite(tree: Tree, replacement: String)

  def catsSyntaxImport(implicit doc: SemanticDocument): Patch =
    Patch.addGlobalImport(Symbol("cats/syntax/all."))

  def preferCatsSyntaxRewrites(tree: Tree): List[Rewrite] =
    tree.collect { case term: Term =>
      preferCatsSyntaxRewrite(term)
    }.flatten

  def simplifyExpressionRewrites(tree: Tree): List[Rewrite] =
    tree.collect { case term: Term =>
      simplifyExpressionRewrite(term)
    }.flatten

  def preferCatsSyntaxRewrite(term: Term): Option[Rewrite] =
    pureSyntax(term)
      .orElse(raiseErrorSyntax(term))
      .orElse(mapSyntax(term))
      .orElse(flatMapSyntax(term))

  def simplifyExpressionRewrite(term: Term): Option[Rewrite] =
    voidSyntax(term)
      .orElse(asSyntax(term))
      .orElse(flatMapPureSyntax(term))
      .orElse(sequenceSyntax(term))
      .orElse(mapNSyntax(term))
      .orElse(optionSyntax(term))
      .orElse(eitherCondSyntax(term))

  private def pureSyntax(term: Term): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(
                TypeclassApply(typeclassName, List(effectType)),
                name
              ) if name.value == "pure" && PureTypeclasses(typeclassName) =>
            singleArg(apply.argClause.values).map(value =>
              Rewrite(term, s"${value.syntax}.pure[${effectType.syntax}]")
            )
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def raiseErrorSyntax(term: Term): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case applyType: Term.ApplyType =>
            applyType.fun match {
              case Term.Select(
                    TypeclassApply(typeclassName, List(effectType)),
                    name
                  )
                  if name.value == "raiseError" &&
                    RaiseErrorTypeclasses(typeclassName) =>
                for {
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

  private def mapSyntax(term: Term): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case receiverApply: Term.Apply =>
            receiverApply.fun match {
              case Term.Select(TypeclassApply(typeclassName, List(_)), name)
                  if name.value == "map" && MapTypeclasses(typeclassName) =>
                for {
                  effect <- singleArg(receiverApply.argClause.values)
                  function <- singleArg(apply.argClause.values)
                } yield Rewrite(
                  term,
                  s"${effect.syntax}.map(${function.syntax})"
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

  private def flatMapSyntax(term: Term): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case receiverApply: Term.Apply =>
            receiverApply.fun match {
              case Term.Select(TypeclassApply(typeclassName, List(_)), name)
                  if name.value == "flatMap" &&
                    FlatMapTypeclasses(typeclassName) =>
                for {
                  effect <- singleArg(receiverApply.argClause.values)
                  function <- singleArg(apply.argClause.values)
                } yield Rewrite(
                  term,
                  s"${effect.syntax}.flatMap(${function.syntax})"
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

  private def voidSyntax(term: Term): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(effect, Term.Name("as"))
              if singleArg(apply.argClause.values).contains(Lit.Unit()) =>
            Some(Rewrite(term, s"${effect.syntax}.void"))
          case Term.Select(effect, Term.Name("map"))
              if singleArg(apply.argClause.values).exists(UnitLambda.unapply) =>
            Some(Rewrite(term, s"${effect.syntax}.void"))
          case _ =>
            None
        }
      case _ =>
        None
    }

  private def asSyntax(term: Term): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(effect, Term.Name("map")) =>
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

  private def flatMapPureSyntax(term: Term): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(effect, Term.Name("flatMap")) =>
            singleArg(apply.argClause.values)
              .collect { case lambda: Term.Function =>
                lambda
              }
              .flatMap { lambda =>
                for {
                  param <- singleParam(lambda)
                  (body, _) <- PureBody.unapply(lambda.body)
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

  private def sequenceSyntax(term: Term): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(effect, Term.Name("flatMap")) =>
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

  private def mapNSyntax(term: Term): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case Term.Select(firstEffect, Term.Name("flatMap")) =>
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
                case Term.Select(effect, Term.Name("map")) => effect
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

  private def optionSyntax(term: Term): Option[Rewrite] =
    term match {
      case Term.If(
            NullComparison(value, "=="),
            none,
            someApply: Term.Apply
          ) if isNone(none) =>
        someValue(someApply)
          .filter(some => sameSyntax(value, some))
          .map(_ => Rewrite(term, s"Option(${value.syntax})"))
      case Term.If(
            NullComparison(value, "!="),
            someApply: Term.Apply,
            none
          ) if isNone(none) =>
        someValue(someApply)
          .filter(some => sameSyntax(value, some))
          .map(_ => Rewrite(term, s"Option(${value.syntax})"))
      case _ =>
        None
    }

  private def eitherCondSyntax(term: Term): Option[Rewrite] =
    term match {
      case Term.If(
            condition,
            RightValue(right),
            LeftValue(left)
          ) =>
        Some(
          Rewrite(
            term,
            s"Either.cond(${condition.syntax}, ${right.syntax}, ${left.syntax})"
          )
        )
      case Term.If(
            condition,
            LeftValue(left),
            RightValue(right)
          ) =>
        Some(
          Rewrite(
            term,
            s"Either.cond(!(${condition.syntax}), ${right.syntax}, ${left.syntax})"
          )
        )
      case _ =>
        None
    }

  private object TypeclassApply {
    def unapply(term: Term): Option[(String, List[Type])] =
      term match {
        case applyType: Term.ApplyType =>
          applyType.fun match {
            case Term.Name(typeclass) =>
              Some(typeclass -> applyType.targClause.values)
            case Term.Select(_, Term.Name(typeclass)) =>
              Some(typeclass -> applyType.targClause.values)
            case _ =>
              None
          }
        case _ =>
          None
      }
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

  private object PureBody {
    def unapply(term: Term): Option[(Term, Type)] =
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
            case Term.Select(
                  TypeclassApply(typeclassName, List(effectType)),
                  name
                ) if name.value == "pure" && PureTypeclasses(typeclassName) =>
              singleArg(apply.argClause.values).map(body => body -> effectType)
            case _ =>
              None
          }
        case _ =>
          None
      }
  }

  private object RightValue {
    def unapply(term: Term): Option[Term] =
      term match {
        case apply: Term.Apply if isNamedApply(apply, "Right") =>
          singleArg(apply.argClause.values)
        case _ =>
          None
      }
  }

  private object LeftValue {
    def unapply(term: Term): Option[Term] =
      term match {
        case apply: Term.Apply if isNamedApply(apply, "Left") =>
          singleArg(apply.argClause.values)
        case _ =>
          None
      }
  }

  private val PureTypeclasses: Set[String] =
    Set("Applicative", "Monad", "MonadThrow", "Sync", "Async", "IO")

  private val RaiseErrorTypeclasses: Set[String] =
    Set("ApplicativeError", "MonadError", "MonadThrow", "Sync", "Async", "IO")

  private val MapTypeclasses: Set[String] =
    Set("Functor", "Applicative", "Monad", "FlatMap", "MonadThrow")

  private val FlatMapTypeclasses: Set[String] =
    Set("FlatMap", "Monad", "MonadThrow", "Sync", "Async", "IO")

  private def namedParam(param: Term.Param): Option[String] =
    param.name match {
      case name: Name if name.value != "_" => Some(name.value)
      case _                               => None
    }

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

  private def someValue(apply: Term.Apply): Option[Term] =
    if (isNamedApply(apply, "Some")) singleArg(apply.argClause.values)
    else None

  private def isNamedApply(apply: Term.Apply, name: String): Boolean =
    apply.fun match {
      case Term.Name(`name`)                 => true
      case Term.Select(_, Term.Name(`name`)) => true
      case _                                 => false
    }

  private def isNone(term: Term): Boolean =
    term match {
      case Term.Name("None")                    => true
      case Term.Select(_, Term.Name("None"))    => true
      case Term.ApplyType(Term.Name("None"), _) => true
      case _                                    => false
    }
}
