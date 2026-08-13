package fix.idioms

import scala.meta._

/** Small term shapes the idiom rules share.
  *
  * These duplicate helpers that `CatsExpressionRules` keeps private. They are
  * repeated rather than exposed because that object's members are part of a
  * published, Mima-checked seam, and the idiom rules need to grow their own
  * shapes without pinning its internals.
  */
private[fix] object TermShapes {

  def singleArg(args: List[Term]): Option[Term] =
    args match {
      case List(arg) => Some(arg)
      case _         => None
    }

  def singleParam(function: Term.Function): Option[Term.Param] =
    function.paramClause.values match {
      case List(param) => Some(param)
      case _           => None
    }

  def namedParam(param: Term.Param): Option[String] =
    param.name match {
      case name: Name if name.value != "_" => Some(name.value)
      case _                               => None
    }

  /** Whether `name` occurs anywhere in `tree`, ignoring shadowing.
    *
    * Every caller uses this to *suppress* a rewrite, so over-approximating is
    * safe: a shadowed occurrence costs a rewrite that would have been valid.
    * Under-approximating would emit one that is not.
    */
  def references(tree: Tree, name: String): Boolean =
    tree.collect { case Term.Name(`name`) => () }.nonEmpty

  /** A lambda parameter list as it is written, parenthesised when it must be.
    */
  def paramSyntax(params: List[Term.Param]): String =
    params match {
      case List(param) => param.syntax
      case many        => many.map(_.syntax).mkString("(", ", ", ")")
    }

  /** A name not already referenced in `scope`, starting from `preferred`. */
  def freshName(preferred: String, scope: Tree): String =
    Iterator
      .iterate(preferred)(name => s"${name}1")
      .find(name => !references(scope, name))
      .getOrElse(preferred)

  /** `x == null`, `x eq null`, and their negations, with the operand and
    * whether the test means "is null".
    */
  object NullTest {
    private val IsNull = Set("==", "eq")
    private val NotNull = Set("!=", "ne")

    def unapply(term: Term): Option[(Term, Boolean)] =
      term match {
        case infix: Term.ApplyInfix
            if singleArg(infix.argClause.values).exists(_.is[Lit.Null]) =>
          if (IsNull.contains(infix.op.value)) Some(infix.lhs -> true)
          else if (NotNull.contains(infix.op.value)) Some(infix.lhs -> false)
          else None
        case _ =>
          None
      }
  }

  /** A curried two-argument-list call `receiver.name(first)(second)`. */
  object CurriedCall {
    def unapply(term: Term): Option[(Term, String, Term, Term)] =
      term match {
        case outer: Term.Apply =>
          outer.fun match {
            case inner: Term.Apply =>
              inner.fun match {
                case Term.Select(receiver, Term.Name(method)) =>
                  for {
                    first <- singleArg(inner.argClause.values)
                    second <- singleArg(outer.argClause.values)
                  } yield (receiver, method, first, second)
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
}
