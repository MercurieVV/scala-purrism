package fix.arrow

import fix.arrow.ArrowIR
import fix.arrow.ArrowIR._

/** Decides whether a normalized `ArrowIR` is worth emitting.
  *
  * The honest limit of this whole approach: the point-free form of an arbitrary
  * for-comprehension is *correct* but frequently *less readable* than the
  * source. A rule that always rewrote would trade a clear `for` for a wall of
  * `&&&`/`.local`/`.map` plumbing. So the budget is the gate that keeps the
  * rule from firing where the output would read worse, and a declined site is a
  * correct outcome, not a failure.
  */
object ReadabilityBudget {

  /** Node kinds that exist only to move data into the shape the next arrow
    * wants, rather than to do work. A little is fine; a lot means the rewrite
    * is mostly plumbing and should be declined.
    */
  private def plumbingNodes(ir: ArrowIR): Int =
    ArrowIR.fold(ir)(0) {
      case (acc, _: Local)     => acc + 1
      case (acc, Merge(_, Id)) => acc + 1
      case (acc, Merge(Id, _)) => acc + 1
      case (acc, _)            => acc
    }

  val MaxPlumbing = 2

  sealed trait Verdict
  case object Accept extends Verdict
  final case class Decline(reason: String) extends Verdict

  /** A lone effect leaf -- `Kleisli { x => k.run(x) }` reduced to `k` -- is a
    * legal but pointless rewrite. Anything with a composition or an output
    * reshape (pattern B's `k.map(f)`) does remove real input threading and is
    * worth emitting.
    */
  private def isTrivial(ir: ArrowIR): Boolean =
    ir match {
      case _: Eff => true
      case _      => false
    }

  def verdict(ir: ArrowIR, renderedLength: Int, sourceLength: Int): Verdict =
    if (ArrowIR.containsOpaque(ir))
      Decline("body contains a subexpression the rule could not analyse")
    else if (ArrowIR.effectCount(ir) < 1 || isTrivial(ir))
      Decline("no composition to gain")
    else if (plumbingNodes(ir) > MaxPlumbing)
      Decline(
        s"needs more than $MaxPlumbing plumbing steps to stay point-free"
      )
    else if (renderedLength > sourceLength * 3 / 2)
      // The structural plumbing budget already bounds readability; the length
      // check is a secondary guard against pathological bloat only. A
      // point-free form that is merely a little longer than a verbose
      // `for`-comprehension (a typed `.local` costs a few characters) is still
      // an improvement, so only a large blow-up is declined.
      Decline("point-free form is substantially longer than the original")
    else
      Accept
}
