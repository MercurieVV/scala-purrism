/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# The callee is declared in ArrowCrossFileStore.scala, and that is the whole
# point of the fixture: scalafix's symbol table cannot describe a symbol from
# another file in a Scala 3 project -- it resolves classpath symbols out of
# classfiles, and a Scala 3 classfile carries its signature as TASTy, which the
# classfile-to-SemanticDB converter cannot read. So `info` comes back empty, the
# callee looks like an ordinary effectful call rather than a Kleisli, and the
# body stays wrapped.
#
# The identical body one file over (ArrowBodyLocalProjection.scala) rewrites,
# which is what made this a silent hole rather than a visible failure: from the
# outside, "declined" and "found nothing" look the same.
#
# KleisliScope closes it by reading the compiler's own `.semanticdb` payloads,
# which do carry full Scala 3 signatures -- the same move PreferKleisli's
# cross-file mode already makes.
 */
package crossfile

import cats.Monad
import cats.data.Kleisli
import cats.syntax.functor._

object ArrowCrossFileCaller {
  def report[F[_]: Monad]: Kleisli[F, (Int, String, Boolean), String] =
    Kleisli { entry =>
      ArrowCrossFileStore.summarise[F]("tag")((entry._1, entry._2)).as("done")
    }
}
