package fix.vocabulary

import munit.FunSuite
import scala.meta._
import scala.meta.dialects.Scala3

class ImportTableResolverSuite extends FunSuite {

  private def resolverFor(imports: String): ImportTableResolver = {
    val source =
      s"""$imports
         |object Test
         |""".stripMargin.parse[Source].get
    ImportTableResolver.fromSource(source)
  }

  test("resolves a name introduced by a plain import") {
    val resolver = resolverFor("import cats.data.Kleisli")
    val tpe = "Kleisli".parse[Type].get
    assertEquals(resolver.resolve(tpe), Option("cats.data.Kleisli"))
  }

  test("resolves a name introduced by a multi-import") {
    val resolver = resolverFor("import cats.data.{Kleisli, EitherT}")
    val tpe = "EitherT".parse[Type].get
    assertEquals(resolver.resolve(tpe), Option("cats.data.EitherT"))
  }

  test("resolves a renamed import to its original FQCN") {
    val resolver = resolverFor("import cats.data.{Kleisli => K}")
    val tpe = "K".parse[Type].get
    assertEquals(resolver.resolve(tpe), Option("cats.data.Kleisli"))
  }

  test("does not resolve a name that could come from a wildcard import") {
    val resolver = resolverFor("import cats.data._")
    val tpe = "Kleisli".parse[Type].get
    assertEquals(resolver.resolve(tpe), Option.empty[String])
  }

  test("does not resolve a name with no matching import") {
    val resolver = resolverFor("")
    val tpe = "Foo".parse[Type].get
    assertEquals(resolver.resolve(tpe), Option.empty[String])
  }

  test("resolves a fully-qualified inline type without needing an import") {
    val resolver = resolverFor("")
    val tpe = "cats.data.Kleisli".parse[Type].get
    assertEquals(resolver.resolve(tpe), Option("cats.data.Kleisli"))
  }

  test("a def's own type parameter is exempt where it's used") {
    val source =
      """def route[Step[_, _]](x: Step[Int, Int]): Step[Int, Int] = x
        |""".stripMargin.parse[Source].get
    val stepUsage = source.collect {
      case n: Type.Name if n.value == "Step" => n
    }.last
    assert(ImportTableResolver.isEnclosingTypeParameter(stepUsage))
  }

  test("a class's own type parameter is exempt where it's used") {
    val source =
      """class Pipeline[Step[_, _]](handle: Step[Int, Int])
        |""".stripMargin.parse[Source].get
    val stepUsage = source.collect {
      case n: Type.Name if n.value == "Step" => n
    }.last
    assert(ImportTableResolver.isEnclosingTypeParameter(stepUsage))
  }

  test("a concrete type name is not a type parameter") {
    val source =
      """def route[Step[_, _]](x: Step[Int, Int]): Step[Int, Int] = x
        |""".stripMargin.parse[Source].get
    val intUsage = source.collect {
      case n: Type.Name if n.value == "Int" => n
    }.head
    assert(!ImportTableResolver.isEnclosingTypeParameter(intUsage))
  }
}
