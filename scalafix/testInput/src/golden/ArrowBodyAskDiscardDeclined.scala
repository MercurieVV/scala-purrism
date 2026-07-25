/*
rules = [PreferArrow]

# Negative fixtures for the `ask <* work` recognition (case 1). Each member
# below matches the naive "some effect, then `.as(input)`" shape but fails
# one of the recognition predicates, so nothing is rewritten.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.functor._
import cats.syntax.apply._

object ArrowBodyAskDiscardDeclined {
  final case class Task(path: String)

  trait Service[F[_]] {
    def validate(task: Task): F[Unit]
  }

  // The callee is a plain `F`-returning method, not a Kleisli -- `bareEffect`
  // requires `KleisliType.isKleisliValue(callee)`, so `parse` returns `None`.
  final class PlainEffect[F[_]: Monad](service: Service[F]) {
    def run(task: Task): F[Task] =
      service.validate(task).as(task)
  }

  // Hand-written point-free form. Neither entry point visits it: the
  // body-only entry needs `KleisliType.isKleisliApply`, which an infix `<*`
  // term is not, and the signature entry needs a single-type-argument
  // `Kleisli[F, A, B]`. This is the idempotence fixture.
  final class AlreadyPointFree[F[_]: Monad] {
    val validate: Kleisli[F, Task, Unit] =
      Kleisli { task => Monad[F].unit }

    def run: Kleisli[F, Task, Task] =
      Kleisli.ask[F, Task] <* validate
  }

  // Peeled as `Reshape.Mapped`, not `Constant` -- falls to the existing
  // input-capturing branch, which needs `aggressive` and is not set here.
  final class MappedDiscard[F[_]: Monad] {
    val validate: Kleisli[F, Task, Unit] =
      Kleisli { task => Monad[F].unit }

    def run: Kleisli[F, Task, Task] =
      Kleisli { task =>
        validate.run(task).map(_ => task)
      }
  }

  // The `as` value is a `Term.Select` (`task.path`), not a `Term.Name`
  // resolving to the input, so case 1 does not claim it.
  final class ProjectedConstant[F[_]: Monad] {
    val validate: Kleisli[F, Task, Unit] =
      Kleisli { task => Monad[F].unit }

    def run: Kleisli[F, Task, String] =
      Kleisli { task =>
        validate.run(task).as(task.path)
      }
  }

  // Same body as the projected sub-shape, without `aggressive`: the
  // conservative length-ratio guard declines and reports why.
  final class ProjectedDeclined[F[_]: Monad] {
    val validatePath: Kleisli[F, String, Unit] =
      Kleisli { path => Monad[F].unit }

    def run: Kleisli[F, Task, Task] =
      Kleisli { task => // assert: PreferArrow
        validatePath.run(task.path).as(task)
      }
  }
}
