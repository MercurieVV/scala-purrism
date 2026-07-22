package fix.arrow

import fix.arrow.ArrowIR._

/** Rewrites an `ArrowIR` to a canonical form, run to a fixpoint.
  *
  * This is what buys the rule's idempotence, which `docs/RULES.md` mandates: a
  * second run of the rule re-parses its own output, and unless that output is
  * already a normal form the rule would keep editing. Every rule here is
  * orientation-preserving (it never grows the tree) and the set is locally
  * confluent, so the fixpoint is unique regardless of application order.
  */
object ArrowNormalize {

  def apply(ir: ArrowIR): ArrowIR = {
    val next = step(ir)
    if (next == ir) ir else apply(next)
  }

  private def step(ir: ArrowIR): ArrowIR =
    ir match {
      // Identity is the unit of composition.
      case AndThen(Id, a) => step(a)
      case AndThen(a, Id) => step(a)

      // Right-associate so a chain has one spine and printing is unambiguous.
      case AndThen(AndThen(a, b), c) => step(AndThen(a, AndThen(b, c)))

      // A pure step on either side of a composition folds into a map / local,
      // which reads better and keeps the effect leaves adjacent.
      case AndThen(a, Lift(f)) => Rmap(step(a), f)
      case AndThen(Lift(f), a) => Local(f, step(a))

      // `*>` is associative, so right-associating gives it one spine too and
      // keeps the printed form free of redundant grouping.
      case ProductR(ProductR(a, b), c) => step(ProductR(a, ProductR(b, c)))

      case AndThen(l, r)            => AndThen(step(l), step(r))
      case Merge(l, r)              => Merge(step(l), step(r))
      case Choice(l, r)             => Choice(step(l), step(r))
      case ProductR(l, r)           => ProductR(step(l), step(r))
      case FlatTap(a, binders, tap) => FlatTap(step(a), binders, step(tap))
      case Local(f, a)              => Local(f, step(a))
      case Rmap(a, f)               => Rmap(step(a), f)
      case leaf                     => leaf
    }
}
