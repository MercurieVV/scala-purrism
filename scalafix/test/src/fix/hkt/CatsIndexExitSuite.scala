package fix.hkt

import munit.FunSuite

import scalafix.v1.Symbol

/** The two index queries the widening rules ask that are not about a single
  * capability's identity: does this operation leave the constructor, and does
  * the index know anything about this constructor at all.
  */
final class CatsIndexExitSuite extends FunSuite {
  private lazy val index: CatsIndex = CatsIndex.load()

  private def exits(method: String): Boolean =
    index.exitsConstructor(Symbol(method))

  test("an operation whose result is still F does not exit") {
    List(
      "cats/Functor#map().",
      "cats/FlatMap#flatMap().",
      "cats/FunctorFilter#filter().",
      "cats/SemigroupK#combineK().",
      "cats/CoflatMap#coflatMap()."
    ).foreach(method => assert(!exits(method), method))
  }

  test("an operation whose result leaves F exits") {
    List(
      "cats/Foldable#toList().",
      "cats/Foldable#foldLeft().",
      "cats/Foldable#foldMap().",
      "cats/UnorderedFoldable#size().",
      "cats/UnorderedFoldable#exists().",
      "cats/Comonad#extract()."
    ).foreach(method => assert(exits(method), method))
  }

  /** The chain `xs.toList.zipWithIndex.map(f)` widens only because the exit is
    * read from the index rather than from a list of method names here.
    */
  test("the exit set is a minority of capabilities, and non-empty") {
    val owners = index.capabilities.valuesIterator.flatten
      .map(_.owner)
      .toSet
    val exiting = owners.count(index.exitsConstructor)
    assert(exiting > 0, "no capability exits its constructor")
    assert(exiting < owners.size, "every capability exits its constructor")
  }

  test("constructors the index has a theory of are recognised") {
    List(
      "scala/collection/immutable/List#",
      "scala/collection/immutable/Vector#",
      "scala/Option#",
      "cats/Eval#",
      "scala/util/Try#"
    ).foreach(constructor =>
      assert(index.knowsConstructor(Symbol(constructor)), constructor)
    )
  }

  /** A rule that reports why it did *not* abstract something asks this first:
    * `Array` and `FloatBuffer` have no Cats capability, so a `var` in a body
    * that takes one is not an obstacle worth naming.
    */
  test("constructors it knows nothing about are not") {
    List(
      "scala/Array#",
      "java/nio/FloatBuffer#",
      "example/Unknown#"
    ).foreach(constructor =>
      assert(!index.knowsConstructor(Symbol(constructor)), constructor)
    )
  }
}
