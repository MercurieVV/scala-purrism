package fix.prefercats

import scala.meta._

/** Substitutes single-use `val`s into their use site, so a block can be
  * compared against a Cats function written as one expression.
  *
  * `{ val t = xs.traverse(x => x); t.map(f) }` and `xs.traverse(x => x).map(f)`
  * compute the same thing, but [[Normalizer]] gives the first a let-application
  * (`App(Lam(1, ...), [rhs])`) and the second a plain chain, so no Cats body
  * inlined in its own source can ever match the block form. Inlining first
  * makes the two normalize alike.
  *
  * Only a `val` referenced *exactly once* is inlined. Zero uses would drop an
  * effect; two or more would duplicate one, and evaluation count is precisely
  * what the rule is not allowed to change (docs/PREFER_CATS_FUNCTIONS.md §2
  * P2). A block that mixes in anything else -- a bare statement, a `def`, a
  * pattern-destructuring `val` -- is returned unchanged from that statement on.
  *
  * Works on `Term`, not on `IR`, so that every subtree of the result is still
  * an original source tree: the matcher binds holes to these subtrees and takes
  * their `pos.text` for the rewrite.
  */
object BlockInliner:

  def inlineLets(term: Term): Term = term match
    case Term.Block(stats) => inlineStats(stats)
    case other             => other

  private def inlineStats(stats: List[Stat]): Term = stats match
    case (t: Term) :: Nil => inlineLets(t)

    case Defn.Val(Nil, List(Pat.Var(name)), _, rhs) :: rest =>
      val rewritten = inlineStats(rest)
      if occurrences(rewritten, name.value) == 1 then
        substitute(rewritten, name.value, inlineLets(rhs))
      else Term.Block(Defn.Val(Nil, List(Pat.Var(name)), None, rhs) :: rest)

    case _ => Term.Block(stats)

  /** How many times a name is referenced, not counting positions where it is a
    * member name (`x.name`) rather than a reference, and stopping at any binder
    * that shadows it.
    */
  private def occurrences(term: Term, name: String): Int =
    var count = 0

    def go(t: Tree, shadowed: Boolean): Unit = t match
      case n: Term.Name =>
        if !shadowed && n.value == name then count += 1

      case Term.Select(qual, _) => go(qual, shadowed)

      case Term.ApplyInfix.After_4_6_0(lhs, _, _, args) =>
        go(lhs, shadowed)
        args.foreach(go(_, shadowed))

      case Term.Function.After_4_6_0(params, body) =>
        go(body, shadowed || params.exists(_.name.value == name))

      case Term.Block(stats) =>
        // A later `val` of the same name shadows ours for the statements that
        // follow it, so the walk has to carry that forward.
        stats.foldLeft(shadowed) { (isShadowed, stat) =>
          stat match
            case Defn.Val(_, pats, _, rhs) =>
              go(rhs, isShadowed)
              isShadowed || pats.exists(patBinds(_, name))
            case other =>
              go(other, isShadowed)
              isShadowed
        }
        ()

      case c: Case =>
        go(c.body, shadowed || patBinds(c.pat, name))

      case other => other.children.foreach(go(_, shadowed))

    go(term, shadowed = false)
    count

  private def patBinds(pat: Pat, name: String): Boolean =
    pat.collect { case Pat.Var(n) if n.value == name => () }.nonEmpty

  /** Replaces every reference to `name` with `replacement`, under the same
    * shadowing rules [[occurrences]] counts by.
    */
  private def substitute(term: Term, name: String, replacement: Term): Term =
    def go(t: Term): Term = t match
      case n: Term.Name if n.value == name => replacement

      case Term.Select(qual, sel) => Term.Select(go(qual), sel)

      case Term.Apply.After_4_6_0(fn, args) =>
        Term.Apply.After_4_6_0(go(fn), Term.ArgClause(args.map(go)))

      case Term.ApplyInfix.After_4_6_0(lhs, op, targs, args) =>
        Term.ApplyInfix.After_4_6_0(
          go(lhs),
          op,
          targs,
          Term.ArgClause(args.map(go))
        )

      case Term.ApplyUnary(op, operand) => Term.ApplyUnary(op, go(operand))

      case Term.Function.After_4_6_0(params, body) =>
        if params.exists(_.name.value == name) then t
        else Term.Function.After_4_6_0(Term.ParamClause(params), go(body))

      case Term.If.After_4_4_0(cond, thenp, elsep, mods) =>
        Term.If.After_4_4_0(go(cond), go(thenp), go(elsep), mods)

      case Term.Match.After_4_9_9(expr, cases, mods) =>
        Term.Match.After_4_9_9(
          go(expr),
          Term.CasesBlock(
            cases.map(c =>
              if patBinds(c.pat, name) then c
              else Case(c.pat, c.cond.map(go), go(c.body))
            )
          ),
          mods
        )

      case Term.Ascribe(expr, tpe) => Term.Ascribe(go(expr), tpe)

      // Anything else (a nested block, a `for`) keeps its own binders and is
      // left alone: the conservative choice is to not inline into it.
      case other => other

    go(term)
