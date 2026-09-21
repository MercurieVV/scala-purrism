/*
rules = [PreferArrow]

PreferArrow.reportSkips = true

# A Kleisli body the parser gives up on outright -- a bare `throw`, no `for`,
# no `flatMap` chain, no `.run`/`.apply` at all -- so `ArrowParser.parse`
# returns `None` and `compile` yields `Compiled.Skip`. Only reachable with
# `reportSkips`, which is why FindingCatalog's coverage suite needs its own
# fixture rather than reusing an existing decline.
 */
package golden

import cats.data.Kleisli

object ArrowBodyUnrecognisedSkip {
  final case class Task(id: Int)

  def run[F[_]]: Kleisli[F, Task, Task] =
    Kleisli { task => // assert: PreferArrow.unrecognised-body
      throw new RuntimeException("boom")
    }
}
