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

      // `<*` is associative too: both groupings run a, b, c and keep a's
      // value. Right-associate it into one printable spine.
      case ProductL(ProductL(a, b), c) => step(ProductL(a, ProductL(b, c)))

      // `a <* ask` keeps a's value and the identity arrow has no observable
      // effect, so the identity can be removed. The mirror `Id <* a` must not
      // collapse: it is precisely the shape that runs a for its effect.
      case ProductL(a, Id) => step(a)

      case AndThen(l, r)            => AndThen(step(l), step(r))
      case Merge(l, r)              => Merge(step(l), step(r))
      case Choice(l, r)             => Choice(step(l), step(r))
      case ProductR(l, r)           => ProductR(step(l), step(r))
      case ProductL(l, r)           => ProductL(step(l), step(r))
      case FlatTap(a, binders, tap) => FlatTap(step(a), binders, step(tap))
      case Local(f, a)              => Local(f, step(a))
      case Rmap(a, f)               => Rmap(step(a), f)
      case As(a, v)                 => As(step(a), v)
      case leaf                     => leaf
    }
}
