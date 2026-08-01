package fix

import scala.meta._

import fix.catsexpr.CatsFacts

/** Unit coverage for the Cats expression rewrites, driven by a fake
  * [[fix.catsexpr.CatsFacts]] so the rendering and fixed-point properties can
  * be checked with no compiler in the loop (`docs/RULES.md`).
  *
  * Whether a rewrite is *allowed* to fire is a symbol question, and that is
  * pinned by the executed fixtures under `scalafix/testInput/src/golden`,
  * including the `CatsNegative*` files. What is checked here is what the
  * rewrites produce once allowed, and that running them again produces nothing.
  */
final class CatsExpressionRulesSuite extends munit.FunSuite {

  private val facts = CatsFacts.bySpelling()

  test("PreferCatsSyntax output is a fixed point") {
    val alreadyRewritten = parseSource(
      """final class Service[F[_]] {
        |  def build(id: String): F[String] =
        |    id.pure[F]
        |
        |  def fail(error: Throwable): F[String] =
        |    error.raiseError[F, String]
        |
        |  def label(seed: F[Int]): F[String] =
        |    seed.map(value => value.toString)
        |
        |  def next(seed: F[Int], render: Int => F[String]): F[String] =
        |    seed.flatMap(value => render(value))
        |}""".stripMargin
    )

    assertEquals(PreferCatsSyntax.rewrites(alreadyRewritten, facts), Nil)
  }

  test("SimplifyCatsExpressions output is a fixed point") {
    val alreadyRewritten = parseSource(
      """final class Service[F[_]] {
        |  def discard(seed: F[Int]): F[Unit] =
        |    seed.void
        |
        |  def replace(seed: F[Int]): F[String] =
        |    seed.as("done")
        |
        |  def mapped(seed: F[Int]): F[String] =
        |    seed.map(value => value.toString)
        |
        |  def sequence(first: F[Unit], second: F[String]): F[String] =
        |    first *> second
        |
        |  def combine(first: F[Int], second: F[String]): F[(Int, String)] =
        |    (first, second).mapN((value, label) => (value, label))
        |
        |  def optional(value: String): Option[String] =
        |    Option(value)
        |
        |  def validated(valid: Boolean, value: String): Either[String, String] =
        |    Either.cond(valid, value, "invalid")
        |}""".stripMargin
    )

    assertEquals(SimplifyCatsExpressions.rewrites(alreadyRewritten, facts), Nil)
  }

  test("rewriting twice reaches the same result as rewriting once") {
    val source = parseSource(
      """final class Service[F[_]] {
        |  def discard(seed: F[Int]): F[Unit] =
        |    seed.map(_ => ())
        |}""".stripMargin
    )

    val once = SimplifyCatsExpressions.rewrites(source, facts)
    assertEquals(once, List("seed.void"))

    val applied = parseSource(
      """final class Service[F[_]] {
        |  def discard(seed: F[Int]): F[Unit] =
        |    seed.void
        |}""".stripMargin
    )
    assertEquals(SimplifyCatsExpressions.rewrites(applied, facts), Nil)
  }

  test("a receiver the facts do not call Cats is left alone") {
    val nonCats = CatsFacts.bySpelling(catsReceivers = Some(Set.empty))
    val source = parseSource(
      """final class Service {
        |  def constant(values: List[Int]): List[Int] =
        |    values.map(_ => 42)
        |}""".stripMargin
    )

    assertEquals(SimplifyCatsExpressions.rewrites(source, nonCats), Nil)
    assertEquals(
      SimplifyCatsExpressions.rewrites(source, facts),
      List("values.as(42)")
    )
  }

  test("a typeclass the facts do not resolve is left alone") {
    val noTypeclasses = CatsFacts.bySpelling(typeclasses = Map.empty)
    val source = parseSource(
      """final class Service[F[_]] {
        |  def build(id: String): F[String] =
        |    Applicative[F].pure(id)
        |}""".stripMargin
    )

    assertEquals(PreferCatsSyntax.rewrites(source, noTypeclasses), Nil)
    assertEquals(
      PreferCatsSyntax.rewrites(source, facts),
      List("id.pure[F]")
    )
  }

  test("the added rewrites are fixed points") {
    val alreadyRewritten = parseSource(
      """final class Service[F[_]] {
        |  def join(nested: F[F[Int]]): F[Int] =
        |    nested.flatten
        |
        |  def unchanged(seed: F[Int]): F[Int] =
        |    seed
        |
        |  def bind(seed: F[Int], render: Int => F[String]): F[String] =
        |    seed.flatMap(render)
        |
        |  def collect(ids: List[Int], load: Int => F[String]): F[List[String]] =
        |    ids.traverse(load)
        |
        |  def guarded(enabled: Boolean, record: F[Unit]): F[Unit] =
        |    record.whenA(enabled)
        |
        |  def orDefault(id: Option[Int], load: Int => F[String]): F[String] =
        |    id.fold("missing".pure[F])(load)
        |
        |  def paired(ids: F[Int], load: Int => F[String]): F[(Int, String)] =
        |    ids.mproduct(load)
        |}""".stripMargin
    )

    assertEquals(SimplifyCatsExpressions.rewrites(alreadyRewritten, facts), Nil)
  }

  test("an outer match suppresses the inner one it contains") {
    val source = parseSource(
      """final class Service[F[_]] {
        |  def bind(seed: F[Int]): F[String] =
        |    seed.map(_ => "done").flatten
        |}""".stripMargin
    )

    // The inner `seed.map(_ => "done")` is an `as` on its own, but rewriting
    // both would patch overlapping ranges.
    assertEquals(
      SimplifyCatsExpressions.rewrites(source, facts),
      List("""seed.flatMap(_ => "done")""")
    )
  }

  test("mproduct declines when the pair is built in the other order") {
    val source = parseSource(
      """final class Service[F[_]] {
        |  def paired(ids: F[Int], load: Int => F[String]): F[(String, Int)] =
        |    ids.flatMap(id => load(id).map(name => (name, id)))
        |}""".stripMargin
    )

    assertEquals(SimplifyCatsExpressions.rewrites(source, facts), Nil)
  }

  private def parseSource(source: String): Source =
    dialects
      .Scala3(source)
      .parse[Source]
      .get
}
