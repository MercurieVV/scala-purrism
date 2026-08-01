package fix.prefercats

import scala.meta._

/** The names a term leaves free, in first-occurrence order.
  *
  * Only meaningful next to [[Normalizer]]: a fragment candidate has no
  * declaration to take its parameter list from, so its free names stand in for
  * one, and `Normalizer.normalize(term, scope)` assigns de Bruijn index `i` to
  * `scope(i)`. Both must therefore agree on *which* names are free and in what
  * order, so this walk mirrors the Normalizer's own: the `name` of a
  * `Term.Select` and the operator of an infix application are member names, not
  * references, and never appear here.
  */
object FreeNames:

  def of(term: Term): List[String] =
    val seen = List.newBuilder[String]
    var emitted = Set.empty[String]

    def emit(name: String, bound: Set[String]): Unit =
      if !bound.contains(name) && !emitted.contains(name) then
        emitted += name
        seen += name

    def patNames(p: Pat): Set[String] =
      p.collect { case Pat.Var(name) => name.value }.toSet

    def go(t: Tree, bound: Set[String]): Unit = t match
      case name: Term.Name => emit(name.value, bound)

      case Term.Select(qual, _) => go(qual, bound)

      case Term.ApplyInfix.After_4_6_0(lhs, _, _, args) =>
        go(lhs, bound)
        args.foreach(go(_, bound))

      case Term.ApplyUnary(_, operand) => go(operand, bound)

      case Term.Function.After_4_6_0(params, body) =>
        go(body, bound ++ params.map(_.name.value))

      case Term.Block(stats) =>
        // A `val` in a block binds for the statements that follow it, so the
        // scope grows as the block is walked rather than all at once.
        stats.foldLeft(bound) { (scope, stat) =>
          stat match
            case v: Defn.Val =>
              go(v.rhs, scope)
              scope ++ v.pats.flatMap(patNames)
            case d: Defn.Def =>
              go(
                d.body,
                scope ++ d.paramClauses.flatMap(_.values.map(_.name.value))
              )
              scope + d.name.value
            case other =>
              go(other, scope)
              scope
        }
        ()

      case Term.ForYield.After_4_9_9(enums, body) => goFor(enums, body, bound)
      case Term.For.After_4_9_9(enums, body)      => goFor(enums, body, bound)

      case Term.Match.After_4_9_9(expr, cases, _) =>
        go(expr, bound)
        cases.foreach(c => go(c.body, bound ++ patNames(c.pat)))

      case _: Lit => ()

      case other => other.children.foreach(go(_, bound))

    def goFor(enums: List[Enumerator], body: Term, bound: Set[String]): Unit =
      val finalScope: Set[String] = enums.foldLeft(bound) {
        (scope, generator) =>
          generator match
            case Enumerator.Generator(pat, rhs) =>
              go(rhs, scope)
              scope ++ patNames(pat)
            case Enumerator.CaseGenerator(pat, rhs) =>
              go(rhs, scope)
              scope ++ patNames(pat)
            case Enumerator.Val(pat, rhs) =>
              go(rhs, scope)
              scope ++ patNames(pat)
            case Enumerator.Guard(cond) =>
              go(cond, scope)
              scope
            case _ => scope
      }
      go(body, finalScope)

    go(term, Set.empty)
    seen.result()
