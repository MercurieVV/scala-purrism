package fix.architecture

import scalafix.v1._

/** The `cats.arrow` typeclasses that qualify a binary type constructor as an
  * "arrow slot", per docs/superpowers/specs/2026-09-10-require-arrow-
  * architecture-design.md. A curated, closed list -- the same convention
  * `fix.hkt.CatsIndex` uses for typeclass membership -- rather than a dynamic
  * hierarchy walk: cats.arrow's own family is small and fixed.
  */
object ArrowFamily {
  val roots: Set[String] = Set(
    "cats/arrow/Arrow#",
    "cats/arrow/ArrowChoice#",
    "cats/arrow/Category#",
    "cats/arrow/Compose#",
    "cats/arrow/Strong#",
    "cats/arrow/Choice#"
  )

  def isArrowFamily(symbol: Symbol): Boolean = roots.contains(symbol.value)
}
