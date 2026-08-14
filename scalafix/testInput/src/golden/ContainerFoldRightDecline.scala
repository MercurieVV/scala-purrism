/*
rules = [PreferContainerTypeclasses]
 */
package golden

/** `foldRight` is not a drop-in.
  *
  * Cats' `Foldable#foldRight` takes an `Eval[B]` and an
  * `(A, Eval[B]) => Eval[B]`, so the stdlib call does not survive the widening
  * even though the name matches. The index therefore maps no `foldRight`, and
  * a body that uses one declines rather than producing `Found: Either[…],
  * Required: cats.Eval[Any]`.
  */
final class ContainerFoldRightDecline {
  private def sequence[A](
      items: List[Either[String, A]]
  ): Either[String, List[A]] =
    items.foldRight(Right(List.empty[A]): Either[String, List[A]]) {
      (item, acc) => acc.flatMap(rest => item.map(value => value :: rest))
    }
}
