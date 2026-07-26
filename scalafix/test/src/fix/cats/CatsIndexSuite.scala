package fix.prefercats

final class CatsIndexSuite extends munit.FunSuite {

  private lazy val index = CatsIndex.load()

  test("the index is non-empty") {
    assert(
      index.nonEmpty,
      "expected the checked-in Cats index to contain at least one record"
    )
  }

  test("every milestone-1 symbol from #96 is present") {
    val expectedSymbols = List(
      "cats/Functor#void().",
      "cats/Functor#as().",
      "cats/Apply#productR().",
      "cats/Apply#productL().",
      "cats/Foldable#foldMap().",
      "cats/Traverse#sequence()."
    )
    val actualSymbols = index.map(_.symbol).toSet
    expectedSymbols.foreach { sym =>
      assert(actualSymbols.contains(sym), s"missing milestone-1 symbol: $sym")
    }
  }

  test("every milestone-1 record is public/renderable") {
    index.foreach { fn =>
      assert(
        fn.render.isDefined,
        s"${fn.symbol} has no render template, but D2 has no fixture for this phase"
      )
    }
  }

  test("record hashes are consistent with their bodies") {
    index.foreach { fn =>
      assertEquals(fn.hash, IR.hash(fn.body), s"stale hash for ${fn.symbol}")
    }
  }

  test("loading fails loudly on a cats-core version mismatch") {
    val mismatched = CatsIndex.render("9.9.9", Nil)
    intercept[IllegalStateException] {
      CatsIndex.parseDocument(mismatched)
    }
  }

  test("IR canonical form round-trips through parse") {
    index.foreach { fn =>
      assertEquals(IR.parse(IR.canonical(fn.body)), fn.body)
    }
  }
}
