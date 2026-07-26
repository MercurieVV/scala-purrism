/*
rules = [DisableSyntax]

# Inspection-only fixture for `fix.prefercats.Normalizer`/`CandidateExtractor`,
# in the style of `golden/ArrowKleisliTypes.scala`: no rule rewrites it,
# `NormalizerSuite`/`CandidateExtractorSuite` read its SemanticDB directly.
 */
package golden

object PreferCatsNormalizerFixtures {

  // Alpha-renaming: same shape, different local names.
  def sumA(a: Int, b: Int): Int = a + b
  def sumB(x: Int, y: Int): Int = x + y

  // `for` vs explicit `flatMap`/`map` desugaring (E2).
  def viaFor(xs: List[Int], ys: List[Int]): List[Int] =
    for {
      a <- xs
      b <- ys
    } yield a + b

  def viaChain(xs: List[Int], ys: List[Int]): List[Int] =
    xs.flatMap(a => ys.map(b => a + b))

  // Eta-expansion vs direct application (E3).
  def inc(x: Int): Int = x + 1
  def usesEta(xs: List[Int]): List[Int] = xs.map(inc)
  def usesDirect(xs: List[Int]): List[Int] = xs.map(x => inc(x))

  // Evaluation order differs (P1): swapped argument order to the same callee.
  def combine(x: Int, y: Int): Int = x - y
  def orderAB(a: Int, b: Int): Int = combine(a, b)
  def orderBA(a: Int, b: Int): Int = combine(b, a)

  // Evaluation count differs (P2): once (let-bound) vs twice (inlined twice).
  def expensive(x: Int): Int = x * 2
  def evalOnce(x: Int): Int = {
    val y = expensive(x)
    y + y
  }
  def evalTwice(x: Int): Int = expensive(x) + expensive(x)

  // By-name vs strict parameter passing (P3).
  def byNameParam(cond: Boolean, x: => Int): Int = if (cond) x else 0
  def strictParam(cond: Boolean, x: Int): Int = if (cond) x else 0
  def usesByName(c: Boolean): Int = byNameParam(c, 5)
  def usesStrict(c: Boolean): Int = strictParam(c, 5)

  // Mutation (P5) -- must be flagged unusable at extraction time.
  def hasMutation(a: Int): Int = {
    var acc = a
    acc = acc + 1
    acc
  }

  // Nested-candidate suppression: the whole method body is one `for`
  // comprehension, so extraction must report exactly one candidate (the
  // method), not a second one for the nested `for`.
  def outerWithFor(xs: List[Int]): List[Int] =
    for (x <- xs) yield x + 1

  // Two independent helper methods at the same nesting level: neither is
  // nested inside the other, so both must be reported.
  def helperOne(x: Int): Int = x + 1
  def helperTwo(x: Int): Int = x + 2
}
