package fix.opaque

/** Reads the SemanticDB payload of the `testInput` fixture module, which the
  * build compiles with `-Ysemanticdb`. This pins the loader against a real
  * compiler payload rather than a hand-built protobuf.
  */
class SemanticdbIndexSuite extends munit.FunSuite {

  // typeAt canonicalises aliases, so compare against the canonical form.
  private val Str = SemanticdbIndex.canonicalType("scala/Predef.String#")

  private lazy val index = FixtureIndex.index

  test("loads the fixture document") {
    val uris = index.documents.map(_.uri)
    assert(
      uris.exists(_.endsWith("golden/OpaqueSmoke.scala")),
      s"expected the smoke fixture, got $uris"
    )
  }

  test("a case-class field emits four alias symbols") {
    val branch =
      index.symbolInfo.keys.filter(_.contains("userId")).toList.sorted
    // getter, ctor param, apply param, copy param
    assert(
      branch.exists(_.endsWith("#userId.")),
      s"expected a getter symbol among $branch"
    )
    assert(
      branch.exists(_.contains("`<init>`")),
      s"expected a ctor param symbol among $branch"
    )
    assert(
      branch.exists(_.contains("copy")),
      s"expected a copy param symbol among $branch"
    )
    assert(branch.size >= 4, s"expected at least 4 alias symbols, got $branch")
  }

  test("typeAt resolves a plain field to its underlying type") {
    val getter = index.symbolInfo.keys
      .find(sym => sym.endsWith("#userId.") && sym.contains("User"))
      .getOrElse(
        fail(s"no User#userId getter in ${index.symbolInfo.keys.toList.sorted}")
      )

    assertEquals(index.typeAt(Node(getter, TypePath.root)), Some(Str))
  }

  test("typeAt indexes into a type argument") {
    // findById(userId: String): Option[User] -- the return's slot 0 is User.
    val findById = index.symbolInfo.keys
      .find(_.endsWith("findById()."))
      .getOrElse(fail("no findById method symbol"))
    val returnSlot = index.typeAt(Node(findById, TypePath(List(0))))

    assert(
      returnSlot.exists(_.contains("User")),
      s"expected Option's payload to be User, got $returnSlot"
    )
  }

  test("typeAt returns None for a path that does not exist") {
    val getter = index.symbolInfo.keys
      .find(sym => sym.endsWith("#userId.") && sym.contains("User"))
      .getOrElse(fail("no User#userId getter"))

    assertEquals(index.typeAt(Node(getter, TypePath(List(3)))), None)
  }

  test("project symbols are distinguished from library ones") {
    val getter = index.symbolInfo.keys
      .find(sym => sym.endsWith("#userId.") && sym.contains("User"))
      .getOrElse(fail("no User#userId getter"))

    assert(index.isProject(getter), "fixture symbols must be Project")
    assert(
      !index.isProject("scala/Option#"),
      "library symbols have no SymbolInformation here and must be Foreign"
    )
  }

  test("occurrences are found across the document set") {
    val getter = index.symbolInfo.keys
      .find(sym => sym.endsWith("#userId.") && sym.contains("User"))
      .getOrElse(fail("no User#userId getter"))

    assert(
      index.occurrencesOf(getter).nonEmpty,
      "the getter must occur at least at its definition"
    )
  }
}
