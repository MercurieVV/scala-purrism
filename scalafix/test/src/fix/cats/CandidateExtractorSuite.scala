package fix.prefercats

import scala.meta._

import scalafix.testkit.FixtureDocuments
import scalafix.v1.SemanticDocument

/** `CandidateExtractor.extract` against real compiler-emitted SemanticDB, using
  * `golden/PreferCatsNormalizerFixtures.scala`.
  */
class CandidateExtractorSuite extends munit.FunSuite {

  private implicit lazy val doc: SemanticDocument =
    FixtureDocuments("src/golden/PreferCatsNormalizerFixtures.scala")

  private lazy val objectBody: Template =
    doc.tree.collect {
      case o: Defn.Object if o.name.value == "PreferCatsNormalizerFixtures" =>
        o.templ
    }.head

  private def defCandidate(
      name: String,
      candidates: List[Candidate]
  ): Candidate = {
    val defBody = doc.tree.collect {
      case d: Defn.Def if d.name.value == name => d.body
    }.head
    candidates
      .find(_.term == defBody)
      .getOrElse(sys.error(s"no candidate found for '$name'"))
  }

  test("a method whose whole body is a `for` reports exactly one candidate") {
    val candidates = CandidateExtractor.extract(objectBody)
    val outerBody = doc.tree.collect {
      case d: Defn.Def if d.name.value == "outerWithFor" => d.body
    }.head

    val matching = candidates.filter(_.term == outerBody)
    assertEquals(
      matching.length,
      1,
      "the for-comprehension must not be reported twice"
    )
    assertEquals(matching.head.kind, "method")
  }

  test("nested candidates inside a matched candidate are suppressed") {
    val candidates = CandidateExtractor.extract(objectBody)
    val forTerms = candidates.filter(_.kind == "for")
    // outerWithFor's `for` is nested directly inside its own method body,
    // which already matched -- it must not also surface as a "for" kind.
    val outerBody = doc.tree.collect {
      case d: Defn.Def if d.name.value == "outerWithFor" => d.body
    }.head
    assert(!forTerms.exists(_.term == outerBody))
  }

  test("independent sibling helper methods are both reported") {
    val candidates = CandidateExtractor.extract(objectBody)
    assert(defCandidate("helperOne", candidates).usable)
    assert(defCandidate("helperTwo", candidates).usable)
  }

  test("mutation is flagged unusable") {
    val candidates = CandidateExtractor.extract(objectBody)
    val candidate = defCandidate("hasMutation", candidates)
    assertEquals(candidate.usable, false)
    assertEquals(candidate.unusableReason, Some("mutation"))
  }

  test("a plain method without mutation/throw/return/cast is usable") {
    val candidates = CandidateExtractor.extract(objectBody)
    assert(defCandidate("sumA", candidates).usable)
  }
}
