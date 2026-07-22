/*
rules = [DisableSyntax]

# Inspection-only fixture for `fix.arrow.KleisliType`, in the style of
# `golden/KleisliFlow.scala`: no rule rewrites it, and `KleisliTypeSuite` reads
# its SemanticDB to check that Kleisli identity is decided by symbol rather than
# by spelling.
#
# Every declaration here mirrors a shape found in the reference corpus
# `gh-tasks-llm-executor`, including the two traps: `-->` used as an abstract
# type parameter, and a plain method named `run`.
 */
package golden

import cats.Monad
import cats.data.Kleisli

object ArrowKleisliTypes {

  // Corpus: main.scala:23-24.
  type -->[F[_], A, B] = Kleisli[F, A, B]
  type Flow[F[_]] = [A, B] =>> Kleisli[F, A, B]

  // A chain of aliases, to exercise repeated dealiasing.
  type Step[F[_], A, B] = -->[F, A, B]
  type Direct[F[_], A, B] = Kleisli[F, A, B]

  final class Resolves[F[_]: Monad] {
    // Written as the alias.
    def viaAlias: -->[F, String, Int] = Kleisli(_ => summon[Monad[F]].pure(1))

    // Written as the type-lambda alias.
    def viaFlow: Flow[F][String, Int] = Kleisli(_ => summon[Monad[F]].pure(1))

    // Written fully qualified.
    def viaFullyQualified: cats.data.Kleisli[F, String, Int] =
      Kleisli(_ => summon[Monad[F]].pure(1))

    // Written bare.
    def viaBare: Kleisli[F, String, Int] = Kleisli(_ => summon[Monad[F]].pure(1))

    // No declared type at all -- the shape of the 9 inferred corpus vals.
    val inferred = Kleisli[F, String, Int](_ => summon[Monad[F]].pure(1))

    // A method returning a Kleisli, applied at the use site
    // (corpus: TaskLogger.progress).
    def parameterised[A](label: A => String): Kleisli[F, A, A] =
      Kleisli(a => summon[Monad[F]].pure(a))
  }

  // Corpus trap: `-->` here is an abstract type parameter, not the alias above.
  // A name-based check fuses the two; a symbol-based one must not. `firstArrow`
  // gives the abstract-typed value a real occurrence to resolve.
  final case class ProgramArrows[-->[_, _]](
      first: String --> Int,
      second: Int --> Boolean
  ) {
    def firstArrow: String --> Int = first
  }

  // Corpus trap: `AgentExecutor[F].run(...)` is a plain method named `run`.
  final class Executor[F[_]: Monad] {
    def run(command: String): F[Int] = summon[Monad[F]].pure(0)
  }

  // Corpus trap: a local type that happens to expose `.run`.
  final case class Pipe[A, B](run: A => B)

  val pipeline: Pipe[String, Int] = Pipe(_.length)

  final class Applies[F[_]: Monad] {
    // The three `Kleisli` constructor spellings. Only the middle one was
    // recognised before; the bare form parses without a `Term.Select`.
    val bare = Kleisli { (s: String) => summon[Monad[F]].pure(s.length) }
    val explicit = Kleisli.apply { (s: String) => summon[Monad[F]].pure(s.length) }
    val ascribed = Kleisli[F, String, Int] { s => summon[Monad[F]].pure(s.length) }
  }
}
