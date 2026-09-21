
package golden

import cats.data.Kleisli

object ArrowBodyUnrecognisedSkip {
  final case class Task(id: Int)

  def run[F[_]]: Kleisli[F, Task, Task] =
    Kleisli { task => 
      throw new RuntimeException("boom")
    }
}
