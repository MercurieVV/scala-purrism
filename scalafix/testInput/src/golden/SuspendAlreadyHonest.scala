/*
rules = [SuspendSideEffects]
 */
package golden

import java.nio.file.{Files, Path}

import cats.effect.IO
import cats.effect.Sync

/** Nothing here is reported: each effect is already in a type that says so. */
final class SuspendAlreadyHonest[F[_]: Sync] {
  def record(target: Path, line: String): F[Unit] =
    Sync[F].blocking { Files.writeString(target, line); () }

  def stamp: F[Long] =
    Sync[F].delay(System.nanoTime())

  def eager: IO[Long] =
    IO { System.nanoTime() }

  def pureValue(n: Long): F[Long] =
    Sync[F].pure(n)
}
