package fix.vocabulary

import munit.FunSuite
import scala.meta._
import scala.meta.dialects.Scala3
import scala.meta.internal.{semanticdb => s}

/** Drives [[SemanticIndexResolver]] with a hand-built SemanticDB payload, no
  * compiler in the loop -- same technique as `KleisliLiftScopeSuite`.
  */
class SemanticIndexResolverSuite extends FunSuite {

  private def occurrence(
      pos: Position,
      symbol: String
  ): s.SymbolOccurrence =
    s.SymbolOccurrence(
      range = Some(
        s.Range(pos.startLine, pos.startColumn, pos.endLine, pos.endColumn)
      ),
      symbol = symbol,
      role = s.SymbolOccurrence.Role.REFERENCE
    )

  private def resolverFor(
      code: String,
      occurrences: Source => List[s.SymbolOccurrence],
      symbolInfo: Map[String, s.SymbolInformation] = Map.empty
  ): (SemanticIndexResolver, Source) = {
    val source = code.parse[Source].get
    val document = s.TextDocument(
      uri = "Test.scala",
      text = code,
      occurrences = occurrences(source)
    )
    (SemanticIndexResolver(document, symbolInfo), source)
  }

  private def typeNamed(source: Source, name: String): Type.Name =
    source.collect { case n: Type.Name if n.value == name => n }.last

  test("resolves a type occurrence to its dotted FQCN from the payload") {
    val (resolver, source) = resolverFor(
      code = "def load: Kleisli = null",
      occurrences = src =>
        List(occurrence(typeNamed(src, "Kleisli").pos, "cats/data/Kleisli#"))
    )
    assertEquals(
      resolver.resolve(typeNamed(source, "Kleisli")),
      Option("cats.data.Kleisli")
    )
  }

  test("returns None when there is no occurrence at that position") {
    val (resolver, source) = resolverFor(
      code = "def load: Kleisli = null",
      occurrences = _ => Nil
    )
    assertEquals(
      resolver.resolve(typeNamed(source, "Kleisli")),
      Option.empty[String]
    )
  }

  test("recognizes a type-parameter occurrence as exempt") {
    val symbol = "local0"
    val (resolver, source) = resolverFor(
      code = "def route[Step](x: Step): Step = x",
      occurrences = src => List(occurrence(typeNamed(src, "Step").pos, symbol)),
      symbolInfo = Map(
        symbol -> s.SymbolInformation(
          symbol = symbol,
          kind = s.SymbolInformation.Kind.TYPE_PARAMETER
        )
      )
    )
    assert(resolver.isExempt(typeNamed(source, "Step")))
  }

  test("recognizes an abstract type member occurrence as exempt") {
    val symbol = "probe/Widget#Elem#"
    val (resolver, source) = resolverFor(
      code = "def widen(x: Elem): Elem = x",
      occurrences = src => List(occurrence(typeNamed(src, "Elem").pos, symbol)),
      symbolInfo = Map(
        symbol -> s.SymbolInformation(
          symbol = symbol,
          kind = s.SymbolInformation.Kind.TYPE,
          properties = s.SymbolInformation.Property.ABSTRACT.value
        )
      )
    )
    assert(resolver.isExempt(typeNamed(source, "Elem")))
  }

  test(
    "picks the occurrence whose symbol name matches the token when several share a position"
  ) {
    // A context-bound `[G[_]: Sync]` desugars to an evidence parameter the
    // compiler anchors at the *same* position as the `Sync` token itself, so
    // the payload records two occurrences there. Only one of them is named
    // "Sync".
    val (resolver, source) = resolverFor(
      code = "def load: Sync = null",
      occurrences = src =>
        List(
          occurrence(typeNamed(src, "Sync").pos, "cats/effect/package.Sync#"),
          occurrence(typeNamed(src, "Sync").pos, "probe/Widget#load().[G]")
        )
    )
    assertEquals(
      resolver.resolve(typeNamed(source, "Sync")),
      Option("cats.effect.package.Sync")
    )
  }

  test("does not exempt a concrete class occurrence") {
    val symbol = "cats/data/Kleisli#"
    val (resolver, source) = resolverFor(
      code = "def load: Kleisli = null",
      occurrences =
        src => List(occurrence(typeNamed(src, "Kleisli").pos, symbol)),
      symbolInfo = Map(
        symbol -> s.SymbolInformation(
          symbol = symbol,
          kind = s.SymbolInformation.Kind.CLASS
        )
      )
    )
    assert(!resolver.isExempt(typeNamed(source, "Kleisli")))
  }
}
