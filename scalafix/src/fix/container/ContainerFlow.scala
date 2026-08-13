package fix

import scala.meta._

import fix.hkt.UsageResult

/** Whether a widened parameter stays abstract for its whole life in the body.
  *
  * Solving the constraint set answers "which typeclass covers the operations we
  * recognised". It does not answer "were those all of them", and it says
  * nothing at all about where the result of those operations goes. Both gaps
  * produce a signature that is strictly weaker than the body needs:
  *
  * {{{
  * private def inferClipSize(chunks: Vector[Chunk]): Int =
  *   chunks.map(_.samples).filter(_ > 0)…   // `filter` is not `Functor`
  *
  * private def jsPoints(xs: Vector[(Double, Double)]): String =
  *   xs.map(…).mkString("[", ",", "]")      // `mkString` is not anything
  *
  * def chord(pitches: Seq[V], d: FiniteDuration): Phrase[V] =
  *   Phrase(pitches.map(…), d)              // `Phrase` wants the concrete `Seq`
  * }}}
  *
  * Each compiles as a `Vector`/`Seq` and none compiles as an `S[_]`. So this
  * walks the chain rooted at the parameter and requires two things: every
  * method along it is one the solved constraints provide, and the chain's value
  * is not handed to something that asked for a concrete container.
  */
private[fix] object ContainerFlow {

  def staysAbstract(
      usage: UsageResult.Abstractable
  ): Boolean =
    parameterName(usage).exists { name =>
      val chains = usage.defn.body.collect {
        case reference: Term.Name if reference.value == name =>
          chainOf(reference)
      }
      chains.nonEmpty && chains.forall { chain =>
        methodsOf(chain).forall(accountedFor(_, usage)) && !isArgument(chain)
      }
    }

  /** The name the widened type belongs to. */
  private def parameterName(usage: UsageResult.Abstractable): Option[String] =
    usage.target.parent.collect { case param: Term.Param =>
      param.name.value
    }

  /** The outermost select/apply the reference is the receiver of. */
  private def chainOf(reference: Term): Term =
    reference.parent match {
      case Some(select: Term.Select) if select.qual eq reference =>
        chainOf(select)
      case Some(apply: Term.Apply) if apply.fun eq reference =>
        chainOf(apply)
      case Some(applyType: Term.ApplyType) if applyType.fun eq reference =>
        chainOf(applyType)
      case _ =>
        reference
    }

  /** Every method invoked along the chain's receiver spine.
    *
    * The spine only, not the whole subtree: `users.map(u => u.toUpperCase)`
    * calls `map` on the container and `toUpperCase` on an element, and only the
    * first is a question about the container's typeclass.
    */
  private def methodsOf(chain: Term): List[Term.Name] =
    chain match {
      case Term.Select(qual, name)   => name :: methodsOf(qual)
      case apply: Term.Apply         => methodsOf(apply.fun)
      case applyType: Term.ApplyType => methodsOf(applyType.fun)
      case _                         => Nil
    }

  /** Whether the analyzer accounted for this call.
    *
    * Compared by position against `usage.ops` rather than by re-resolving the
    * symbol, because the symbol on the spine is the *stdlib* one --
    * `scala/collection/immutable/List#map().` -- and mapping that to
    * `cats/Functor#map().` is the analyzer's own job. What matters here is only
    * whether it did it: a spine call missing from `ops` is one the solved
    * constraints do not cover, and `filter` and `mkString` are missing from it.
    */
  private def accountedFor(
      method: Term.Name,
      usage: UsageResult.Abstractable
  ): Boolean =
    usage.ops.exists { op =>
      op.position.start <= method.pos.start && method.pos.end <= op.position.end
    }

  /** Whether the chain's value is passed to something else.
    *
    * `Phrase(pitches.map(f), d)` asked for a `Seq`, and nothing in the
    * constraint set makes an `S[_]` into one. The body's own result is fine --
    * that is the signature being widened -- but an argument is a second
    * signature this rule was not given and cannot change.
    */
  private def isArgument(chain: Term): Boolean =
    chain.parent.exists {
      case _: Term.ArgClause => true
      case _                 => false
    }
}
