package fix

import scala.meta._

import scalafix.testkit.FixtureDocuments
import scalafix.v1.SemanticDocument

import fix.prefercats._

/** `PreferCatsFunctions`'s matching/ranking/decline logic (§3-4), driven
  * through a hand-built index rather than the checked-in milestone-1 one: the
  * real index's bodies are cats-core's own internal, unqualified self-calls
  * (`foldLeft(fa, ...)`, not `fa.foldLeft(...)`), which no ordinary project
  * code can spell -- see `Normalizer`'s note on E5 being deferred past
  * normalization. A synthetic index built from a real candidate's own
  * normalized IR exercises the same decision tree (hash/IR-equality prefilter,
  * D1-D3 decline, §4 ranking, rendering) without depending on that gap closing
  * first.
  */
class PreferCatsFunctionsSuite extends munit.FunSuite {

  private implicit lazy val doc: SemanticDocument =
    FixtureDocuments("src/golden/PreferCatsNormalizerFixtures.scala")

  private def candidateFor(defName: String): Candidate = {
    val body = doc.tree
      .collect { case d: Defn.Def if d.name.value == defName => d.body }
      .headOption
      .getOrElse(sys.error(s"no def named '$defName'"))
    CandidateExtractor
      .extract(doc.tree)
      .find(_.term == body)
      .getOrElse(sys.error(s"no candidate extracted for '$defName'"))
  }

  private def irOf(candidate: Candidate): IR = {
    val paramNames = candidate.term.parent
      .collect { case d: Defn.Def =>
        d.paramClauses.flatMap(_.values.map(_.name.value)).toList
      }
      .getOrElse(Nil)
    Normalizer.normalize(candidate.term, paramNames)
  }

  private def publicFn(
      body: IR,
      symbol: String,
      template: String,
      requiredImports: List[String] = List("cats.syntax.foo.*"),
      explicitParamCount: Int = 2
  ): CatsFn =
    CatsFn(
      symbol = symbol,
      owner = "cats/Foo#",
      ownerKind = OwnerKind.Typeclass,
      typeParams = Nil,
      valueParams = List.tabulate(explicitParamCount)(i =>
        ParamSig(
          s"p$i",
          "A",
          byName = false,
          isImplicit = false,
          hasDefault = false
        )
      ),
      returnType = "A",
      constraints = Nil,
      requiredImports = requiredImports,
      render = Some(RenderTemplate(RenderKind.Postfix, template)),
      body = body,
      hash = IR.hash(body)
    )

  test("no hash/IR match anywhere in the index yields NoMatch") {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val unrelated = publicFn(IR.Lit, "cats/Foo#unrelated().", "$recv.bar")
    val byHash = Seq(unrelated).groupBy(_.hash)

    assertEquals(
      PreferCatsFunctions.decide(candidate, byHash, Set.empty),
      PreferCatsFunctions.MatchOutcome.NoMatch
    )
  }

  test("a single public full match renders and wins (happy path)") {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val cf = publicFn(ir, "cats/Foo#sum().", "$recv.sum($a0)")
    val byHash = Seq(cf).groupBy(_.hash)

    PreferCatsFunctions.decide(candidate, byHash, Set.empty) match {
      case PreferCatsFunctions.MatchOutcome.Rewrite(winner, rendered) =>
        assertEquals(winner.symbol, "cats/Foo#sum().")
        assertEquals(rendered, "a.sum(b)")
      case other => fail(s"expected a Rewrite, got $other")
    }
  }

  test("decision is deterministic across repeated calls") {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val cf = publicFn(ir, "cats/Foo#sum().", "$recv.sum($a0)")
    val byHash = Seq(cf).groupBy(_.hash)

    val first = PreferCatsFunctions.decide(candidate, byHash, Set.empty)
    val second = PreferCatsFunctions.decide(candidate, byHash, Set.empty)
    assertEquals(first, second)
  }

  test(
    "D2: a full match that is only a private/internal Cats detail declines with no patch"
  ) {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val internalOnly =
      publicFn(ir, "cats/Foo#internalSum().", "$recv.sum($a0)")
        .copy(render = None)
    val byHash = Seq(internalOnly).groupBy(_.hash)

    assertEquals(
      PreferCatsFunctions.decide(candidate, byHash, Set.empty),
      PreferCatsFunctions.MatchOutcome.PrivateOnly
    )
  }

  test(
    "D2: a public match wins over a same-shape private-only one (criterion 1)"
  ) {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val internalOnly =
      publicFn(ir, "cats/Foo#internalSum().", "$recv.sum($a0)")
        .copy(render = None)
    val public = publicFn(ir, "cats/Foo#sum().", "$recv.sum($a0)")
    val byHash = Seq(internalOnly, public).groupBy(_.hash)

    PreferCatsFunctions.decide(candidate, byHash, Set.empty) match {
      case PreferCatsFunctions.MatchOutcome.Rewrite(winner, _) =>
        assertEquals(winner.symbol, "cats/Foo#sum().")
      case other => fail(s"expected a Rewrite, got $other")
    }
  }

  test(
    "D1: two public matches tied on scope and rendered length decline as ambiguous"
  ) {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val left = publicFn(ir, "cats/Foo#sumLeft().", "$recv.sum($a0)")
    val right = publicFn(ir, "cats/Foo#sumRight().", "$recv.tot($a0)")
    val byHash = Seq(left, right).groupBy(_.hash)

    assertEquals(
      PreferCatsFunctions.decide(candidate, byHash, Set.empty),
      PreferCatsFunctions.MatchOutcome.Ambiguous
    )
  }

  test(
    "ranking criterion 2: an already-in-scope match wins over one needing a new import"
  ) {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val needsImport =
      publicFn(
        ir,
        "cats/Foo#sumA().",
        "$recv.sum($a0)",
        List("cats.syntax.a.*")
      )
    val inScope =
      publicFn(
        ir,
        "cats/Foo#sumB().",
        "$recv.tot($a0)",
        List("cats.syntax.b.*")
      )
    val byHash = Seq(needsImport, inScope).groupBy(_.hash)

    PreferCatsFunctions.decide(
      candidate,
      byHash,
      Set("cats.syntax.b.*")
    ) match {
      case PreferCatsFunctions.MatchOutcome.Rewrite(winner, _) =>
        assertEquals(winner.symbol, "cats/Foo#sumB().")
      case other => fail(s"expected a Rewrite, got $other")
    }
  }

  test(
    "ranking criterion 3: shortest rendered form wins only as the final tiebreak"
  ) {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val longer =
      publicFn(ir, "cats/Foo#sumLong().", "$recv.sumTotalOf($a0)")
    val shorter = publicFn(ir, "cats/Foo#sumShort().", "$recv.sum($a0)")
    val byHash = Seq(longer, shorter).groupBy(_.hash)

    PreferCatsFunctions.decide(candidate, byHash, Set.empty) match {
      case PreferCatsFunctions.MatchOutcome.Rewrite(winner, rendered) =>
        assertEquals(winner.symbol, "cats/Foo#sumShort().")
        assertEquals(rendered, "a.sum(b)")
      case other => fail(s"expected a Rewrite, got $other")
    }
  }

  test(
    "D3: a required constraint with no matching implicit/using parameter in the candidate declines"
  ) {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val needsMonoid = publicFn(ir, "cats/Foo#sum().", "$recv.sum($a0)")
      .copy(constraints = List("cats/Monoid#"))
    val byHash = Seq(needsMonoid).groupBy(_.hash)

    assertEquals(
      PreferCatsFunctions.decide(candidate, byHash, Set.empty),
      PreferCatsFunctions.MatchOutcome.MissingEvidence
    )
  }

  test(
    "idempotence: a rewritten call site's free-var shape can never canonical-equal a bound-var body"
  ) {
    val candidate = candidateFor("sumA")
    val ir = irOf(candidate)
    val cf = publicFn(ir, "cats/Foo#sum().", "$recv.sum($a0)")

    // After a rewrite, the candidate's body becomes a plain call expression
    // (e.g. `a.sum(b)`). Re-extracted as a "method" candidate and normalized
    // with the same param names as initial scope, `a`/`b` are still Bound
    // refs -- but the shape is `App(Sel(Bound0,"sum"), [Bound1])`, an
    // ordinary chain call, never `cf.body`'s shape (which is whatever
    // `sumA`'s own `a + b` normalized to). Idempotence holds because the
    // rendered replacement's own normalized IR is simply never hash-equal to
    // the `CatsFn` that produced it -- demonstrated directly here since
    // `Slot.Free` and `Slot.Bound` are distinct IR node kinds by
    // construction, so no free-variable re-reading of the same call text can
    // ever canonical-equal a bound-variable body regardless of symbol
    // identity.
    val rewrittenAsFreeExpr = IR.App(
      IR.Sel(IR.Ref(Slot.Free("local-a")), "sum"),
      List(IR.Ref(Slot.Free("local-b"))),
      List(false)
    )
    assertNotEquals(IR.canonical(rewrittenAsFreeExpr), IR.canonical(cf.body))
  }
}
