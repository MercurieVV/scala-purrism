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

  /** A rewrite, with its replacement built from the *source text* of the parts
    * it keeps.
    *
    * `Tree.syntax` re-prints a tree from its structure, which reflows whatever
    * the author wrote: a multi-line string interpolation comes back split
    * across a dozen lines, and `0xff` comes back as `255`. Since every part
    * these matchers splice came from the file, `pos.text` returns it verbatim.
    */
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
    outermost(tree.collect { case term: Term =>
      simplifyExpressionRewrite(term, facts)
    }.flatten)

  /** Drops any rewrite whose tree sits inside another rewrite's tree.
    *
    * Rewrites are collected over every `Term`, so a nested expression can match
    * on its own while its parent matches too -- `fa.map(f).flatten` is both a
    * `flatten` of a `map` and, inside, a `map`. Emitting both produces two
    * `Patch.replaceTree` calls on overlapping ranges, and the result depends on
    * which one is applied. The outer match describes the larger rewrite, so it
    * wins.
    */
  private def outermost(rewrites: List[Rewrite]): List[Rewrite] =
    rewrites.filterNot { candidate =>
      rewrites.exists(other =>
        !(other.tree eq candidate.tree) &&
          other.tree.pos.start <= candidate.tree.pos.start &&
          candidate.tree.pos.end <= other.tree.pos.end
      )
    }

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
      .orElse(flattenSyntax(term, facts))
      .orElse(identityMapSyntax(term, facts))
      .orElse(mapThenSyntax(term, facts))
      .orElse(whenSyntax(term, facts))
      .orElse(foldPureSyntax(term, facts))
      .orElse(mproductSyntax(term, facts))

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
              s"${value.pos.text}.pure[${effectType.pos.text}]"
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
                  s"${error.pos.text}.raiseError[${effectType.pos.text}, ${resultType.pos.text}]"
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
                  s"${effect.pos.text}.$method(${function.pos.text})"
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
              // Scalameta trees compare by reference, so `contains(Lit.Unit())`
              // is never true and this case used to be dead.
              if facts.isCatsOperation(select) &&
                singleArg(apply.argClause.values).exists(_.is[Lit.Unit]) =>
            Some(Rewrite(term, s"${effect.pos.text}.void"))
          case select @ Term.Select(effect, Term.Name("map"))
              if facts.isCatsOperation(select) &&
                singleArg(apply.argClause.values).exists(UnitLambda.unapply) =>
            Some(Rewrite(term, s"${effect.pos.text}.void"))
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
                Rewrite(term, s"${effect.pos.text}.as(${value.pos.text})")
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
                  s"${effect.pos.text}.map(${lambdaParamSyntax(lambda.paramClause)} => ${body.pos.text})"
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
                Rewrite(term, s"${effect.pos.text} *> ${next.pos.text}")
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
                s"(${firstEffect.pos.text}, ${secondEffect.pos.text}).mapN(${mapNFunction(firstParam, secondFunction)})"
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
          .map(_ => Rewrite(term, s"Option(${value.pos.text})"))
      case Term.If.After_4_4_0(
            NullComparison(value, "!="),
            someApply: Term.Apply,
            none,
            _
          ) if isNone(none, facts) =>
        someValue(someApply, facts)
          .filter(some => sameSyntax(value, some))
          .map(_ => Rewrite(term, s"Option(${value.pos.text})"))
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
                s"Either.cond(${condition.pos.text}, ${right.pos.text}, ${left.pos.text})"
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
                    s"Either.cond(!(${condition.pos.text}), ${right.pos.text}, ${left.pos.text})"
                  )
                )
              case _ =>
                None
            }
        }
      case _ =>
        None
    }

  /** `fa.flatMap(identity)` and `fa.flatMap(a => a)` -> `fa.flatten`. */
  private def flattenSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case select @ Term.Select(effect, Term.Name("flatMap"))
              if facts.isCatsOperation(select) &&
                singleArg(apply.argClause.values).exists(isIdentity) =>
            Some(Rewrite(term, s"${effect.pos.text}.flatten"))
          case _ =>
            None
        }
      case _ =>
        None
    }

  /** `fa.map(identity)` and `fa.map(a => a)` -> `fa`. */
  private def identityMapSyntax(
      term: Term,
      facts: CatsFacts
  ): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case select @ Term.Select(effect, Term.Name("map"))
              if facts.isCatsOperation(select) &&
                singleArg(apply.argClause.values).exists(isIdentity) =>
            Some(Rewrite(term, effect.syntax))
          case _ =>
            None
        }
      case _ =>
        None
    }

  /** `fa.map(f).flatten` -> `fa.flatMap(f)`, and the `sequence` family:
    * `xs.map(f).sequence` -> `xs.traverse(f)`, `xs.map(f).sequence_` ->
    * `xs.traverse_(f)`.
    */
  private def mapThenSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case outer @ Term.Select(inner: Term.Apply, Term.Name(after))
          if MapThenCombinators.contains(after) &&
            facts.isCatsOperation(outer) =>
        inner.fun match {
          case mapSelect @ Term.Select(effect, Term.Name("map"))
              if facts.isCatsOperation(mapSelect) =>
            singleArg(inner.argClause.values).map { function =>
              Rewrite(
                term,
                s"${effect.pos.text}.${MapThenCombinators(after)}(${function.pos.text})"
              )
            }
          case _ =>
            None
        }
      case _ =>
        None
    }

  /** What `receiver.map(f).<name>` collapses to. */
  private val MapThenCombinators: Map[String, String] = Map(
    "flatten" -> "flatMap",
    "sequence" -> "traverse",
    "sequence_" -> "traverse_"
  )

  /** `if (c) fa else F.unit` -> `fa.whenA(c)`, and the mirrored form ->
    * `fa.unlessA(c)`.
    *
    * Both combinators return `F[Unit]`, so this only holds when the effect is
    * already `F[Unit]`; otherwise the conditional and the rewrite have
    * different types.
    */
  private def whenSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case Term.If.After_4_4_0(condition, thenBranch, elseBranch, _) =>
        if (isUnitEffect(elseBranch, facts) && facts.isUnitEffect(thenBranch))
          Some(
            Rewrite(
              term,
              s"${thenBranch.pos.text}.whenA(${condition.pos.text})"
            )
          )
        else if (
          isUnitEffect(thenBranch, facts) && facts.isUnitEffect(elseBranch)
        )
          Some(
            Rewrite(
              term,
              s"${elseBranch.pos.text}.unlessA(${condition.pos.text})"
            )
          )
        else None
      case _ =>
        None
    }

  /** `opt.fold(F.pure(d))(f)` -> `opt.fold(d.pure[F])(f)`, for consistency with
    * `PreferCatsSyntax`.
    */
  private def foldPureSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case outer: Term.Apply =>
        outer.fun match {
          case inner: Term.Apply =>
            inner.fun match {
              case Term.Select(receiver, Term.Name("fold")) =>
                for {
                  empty <- singleArg(inner.argClause.values)
                  (value, effectType) <- pureBody(empty, facts)
                  if !empty.is[Term.ApplyType]
                  function <- singleArg(outer.argClause.values)
                } yield Rewrite(
                  term,
                  s"${receiver.pos.text}.fold(${value.pos.text}.pure[${effectType.pos.text}])(${function.pos.text})"
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

  /** `fa.flatMap(a => g(a).map(b => (a, b)))` -> `fa.mproduct(g)`.
    *
    * The pairing has to be exactly this shape: the second effect is `g` applied
    * to the bound value and nothing else, and the result is the pair in that
    * order. Anything else -- a different order, an extra step, a `g` that reads
    * more than `a` -- is not `mproduct`.
    */
  private def mproductSyntax(term: Term, facts: CatsFacts): Option[Rewrite] =
    term match {
      case apply: Term.Apply =>
        apply.fun match {
          case select @ Term.Select(effect, Term.Name("flatMap"))
              if facts.isCatsOperation(select) =>
            for {
              outerFunction <- singleArg(apply.argClause.values).collect {
                case function: Term.Function => function
              }
              outerParam <- singleParam(outerFunction)
              outerName <- namedParam(outerParam)
              innerApply <- Some(outerFunction.body).collect {
                case inner: Term.Apply => inner
              }
              second <- Some(innerApply.fun).collect {
                case mapSelect @ Term.Select(receiver, Term.Name("map"))
                    if facts.isCatsOperation(mapSelect) =>
                  receiver
              }
              generator <- appliedTo(second, outerName)
              innerFunction <- singleArg(innerApply.argClause.values).collect {
                case function: Term.Function => function
              }
              innerParam <- singleParam(innerFunction)
              innerName <- namedParam(innerParam)
              if isPair(innerFunction.body, outerName, innerName)
            } yield Rewrite(term, s"${effect.pos.text}.mproduct($generator)")
          case _ =>
            None
        }
      case _ =>
        None
    }

  /** The function of `g(name)`, when that is the whole term. */
  private def appliedTo(term: Term, name: String): Option[String] =
    term match {
      case apply: Term.Apply =>
        singleArg(apply.argClause.values).collect {
          case Term.Name(`name`) if !references(apply.fun, name) =>
            apply.fun.syntax
        }
      case _ =>
        None
    }

  /** `(first, second)` in exactly that order. */
  private def isPair(term: Term, first: String, second: String): Boolean =
    term match {
      case Term.Tuple(List(Term.Name(`first`), Term.Name(`second`))) => true
      case _                                                         => false
    }

  /** `identity` or a lambda that returns its own parameter. */
  private def isIdentity(term: Term): Boolean =
    term match {
      case Term.Name("identity") =>
        true
      case function: Term.Function =>
        (for {
          param <- singleParam(function)
          name <- namedParam(param)
        } yield function.body match {
          case Term.Name(`name`) => true
          case _                 => false
        }).getOrElse(false)
      case _ =>
        false
    }

  /** `Typeclass[F].unit` or any Cats `unit` member. */
  private def isUnitEffect(term: Term, facts: CatsFacts): Boolean =
    term match {
      case select @ Term.Select(receiver, Term.Name("unit")) =>
        typeclassEffect(
          receiver,
          CatsFacts.Typeclasses.pure,
          facts
        ).isDefined ||
        facts.isCatsOperation(select)
      case _ =>
        false
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

  /** A lambda that ignores its argument, so `fa.map(f)` is `fa.as(f())`.
    *
    * `exists` rather than `forall` on the parameter: `singleParam` answers
    * `None` for a lambda that takes anything other than one argument, and
    * `None.forall` is vacuously true -- which made every multi-parameter lambda
    * a constant one. `xs.map((v, t) => f(v, t))` then became `xs.as(f(v, t))`,
    * dropping both binders and leaving names that no longer resolve.
    */
  private object ConstantLambda {
    def unapply(term: Term): Option[Term] =
      term match {
        case function: Term.Function
            if singleParam(function).exists(param =>
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
        s"(${firstParam.pos.text}, ${secondParam.pos.text}) => ${secondFunction.body.pos.text}"
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
