package fix.vocabulary

import munit.FunSuite
import scala.meta._
import scala.meta.dialects.Scala3
import scala.meta.internal.{semanticdb => s}

import fix.opaque.SemanticdbIndex

/** Drives the resolver-selection logic `RestrictVocabulary` uses to pick
  * between a compiled payload and the import-table fallback, with a hand-built
  * SemanticDB payload -- no compiler in the loop.
  */
class VocabularyResolversSuite extends FunSuite {

  private def typeNamed(source: Source, name: String): Type.Name =
    source.collect { case n: Type.Name if n.value == name => n }.last

  test("uses the compiled payload when it's current for this exact source") {
    val code = "def load: Kleisli = null"
    val source = code.parse[Source].get
    val kleisli = typeNamed(source, "Kleisli")

    val document = s.TextDocument(
      uri = "Test.scala",
      text = code,
      md5 = SemanticdbIndex.md5(code),
      occurrences = List(
        s.SymbolOccurrence(
          range = Some(
            s.Range(
              kleisli.pos.startLine,
              kleisli.pos.startColumn,
              kleisli.pos.endLine,
              kleisli.pos.endColumn
            )
          ),
          symbol = "cats/data/Kleisli#",
          role = s.SymbolOccurrence.Role.REFERENCE
        )
      )
    )
    val index = new SemanticdbIndex(List(document))

    val resolver = VocabularyResolvers.forSource(source, code, index)

    // No import for Kleisli exists in this source, so only the compiled
    // payload -- not the import-table fallback -- can resolve it.
    assertEquals(resolver.resolve(kleisli), Option("cats.data.Kleisli"))
  }

  test("falls back to the import table when no payload matches this source") {
    val code = "import cats.data.Kleisli\ndef load: Kleisli = null"
    val source = code.parse[Source].get
    val kleisli = typeNamed(source, "Kleisli")

    // The index holds a payload, but for different text -- e.g. stale,
    // compiled before this file was last edited.
    val staleDocument = s.TextDocument(
      uri = "Test.scala",
      text = "def load: Kleisli = null",
      md5 = SemanticdbIndex.md5("def load: Kleisli = null")
    )
    val index = new SemanticdbIndex(List(staleDocument))

    val resolver = VocabularyResolvers.forSource(source, code, index)

    assertEquals(resolver.resolve(kleisli), Option("cats.data.Kleisli"))
  }

  test("falls back to the import table when the index has no documents") {
    val code = "import cats.data.Kleisli\ndef load: Kleisli = null"
    val source = code.parse[Source].get
    val kleisli = typeNamed(source, "Kleisli")

    val resolver =
      VocabularyResolvers.forSource(source, code, new SemanticdbIndex(Nil))

    assertEquals(resolver.resolve(kleisli), Option("cats.data.Kleisli"))
  }
}
