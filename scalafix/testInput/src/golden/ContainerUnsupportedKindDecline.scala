/*
rules = [PreferPolymorphicCollections]
 */
package golden

import cats.syntax.semigroup._
import cats.kernel.instances.list._

final class ContainerUnsupportedKindDecline {
  // The only operation on `xs` is `Semigroup#combine`, an *element*-kinded
  // capability (Star) rather than a container one -- the solver requires
  // every op to be `Unary` before it will widen, so a body whose sole op is
  // `Star`-kinded declines with the type-constructor kind itself.
  private def combined(xs: List[Int], ys: List[Int]): List[Int] = // assert: PreferPolymorphicCollections.unsupported-kind
    xs.combine(ys)
}
