package examples

import cats.effect.Sync
import cats.syntax.all.*

final class UserService[F[_]: Sync](profiles: Profiles[F]) {
  def load(seed: F[Profile]): F[Profile] =
    seed.flatMap(profile => normalize(profile.pure[F]))

  private def normalize[F[_]: Sync](profile: F[Profile]): F[Profile] =
    profile.map(identity)
}

trait Profiles[F[_]] {
  def fetch(id: String): F[Profile]
}

final case class Profile(id: String)
