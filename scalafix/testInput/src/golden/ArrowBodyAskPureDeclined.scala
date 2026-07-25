/*
rules = [PreferArrow]

# Negative fixtures for the `pure`-of-input recognition (case 2). Each member
# below looks close to `task.pure[F]` but fails one of the recognition
# predicates, so nothing is rewritten and no diagnostic is reported.
 */
package golden

import cats.{Applicative, Monad}
import cats.data.Kleisli
import cats.syntax.applicative._

object ArrowBodyAskPureDeclined {
  final case class Task(path: String)

  // The `pure` receiver is a `Term.Select` (`task.path`), not the input --
  // a pure `A => B` would need the `Lift`/`fromFunction` path, out of scope.
  final class PureOfProjection[F[_]: Monad] {
    def echo: Kleisli[F, Task, String] =
      Kleisli { task =>
        task.path.pure[F]
      }
  }

  // The select resolves to `cats/Applicative#pure().`, not the ops symbol
  // `cats/syntax/ApplicativeIdOps#pure().` -- declined by design.
  final class InstancePure[F[_]: Monad] {
    def echo: Kleisli[F, Task, Task] =
      Kleisli { task =>
        Applicative[F].pure(task)
      }
  }

  // The `pure` receiver's symbol is not the arrow's own input.
  final class PureOfCapture[F[_]: Monad](seed: Task) {
    def echo: Kleisli[F, Task, Task] =
      Kleisli { task =>
        seed.pure[F]
      }
  }

  // No declared `Kleisli[F, A, B]`/`-->`/`Flow` type -- `Input.effect` has no
  // `F` to put in `Kleisli.ask[F, A]`, so the site is declined rather than
  // downgraded to an untyped `Kleisli.ask`.
  final class NoDeclaredType[F[_]: Monad] {
    def echo = Kleisli { (task: Task) => task.pure[F] }
  }
}
