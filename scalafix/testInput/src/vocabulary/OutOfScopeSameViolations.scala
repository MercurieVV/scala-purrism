/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["vocabulary\\.inscopeonly.*"]
RequireArrowArchitecture.bannedConstructs = ["scala.meta.Term.If"]
 */
package vocabulary.notinscope

final case class Holder(
  step: cats.data.Kleisli[cats.effect.IO, Int, Int]
) {
  def run(x: Int): Int =
    if (x > 0) x else -x
}
