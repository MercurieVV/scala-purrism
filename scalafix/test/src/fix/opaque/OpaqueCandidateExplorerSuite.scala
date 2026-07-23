package fix.opaque

import metaconfig.Conf
import metaconfig.typesafeconfig._

import fix.PropagateOpaqueTypeConfig

/** Runs the explorer over the compiled `testInput` fixture, so the ranking is
  * checked against real compiler output rather than a hand-built graph.
  */
class OpaqueCandidateExplorerSuite extends munit.FunSuite {

  private lazy val candidates: List[OpaqueCandidate] =
    OpaqueCandidateExplorer.withPlacement(
      FixtureIndex.index,
      OpaqueCandidateExplorer.explore(FixtureIndex.index, FixtureIndex.facts)
    )

  test("the largest flow in the fixture is ranked first") {
    val top = candidates.headOption.getOrElse(fail("no candidates at all"))

    // The `Branch#name` flow is the widest one in `testInput`, so it heads the
    // ranking. Asserting the concrete cluster -- not merely "something is
    // first" -- is what makes this a regression test for the metric.
    //
    // The name is `BranchName2`, not `BranchName`: `OpaqueAnnotations.scala`
    // already declares `opaque type BranchName`, and discovery refuses to mint
    // a second type under an existing type's name -- it takes the next free
    // suffix instead, exactly as it must in a codebase already part-converted.
    assertEquals(top.name, "BranchName2")
    assertEquals(top.owner, "golden/Run#")
    assertEquals(top.underlying, "scala/Predef.String#")
    assert(
      candidates.tail.forall(_.size <= top.size),
      "candidates are not ordered by closure size"
    )
  }

  test("the userId flow is found, with every alias of the field as a seed") {
    val userId = candidates
      .find(candidate => candidate.seeds.exists(_.contains("#userId.")))
      .getOrElse(
        fail(s"no userId cluster among ${candidates.map(_.name)}")
      )

    assertEquals(userId.name, "UserId")
    assertEquals(userId.owner, "_empty_/User#")
    assertEquals(userId.underlying, "scala/Predef.String#")
    // Getter, constructor parameter, `copy` parameter and `apply` parameter.
    assertEquals(userId.seeds.size, 4)
  }

  test("a candidate names a definition file inside the analysed sources") {
    val top = candidates.head
    assert(
      top.definitionFile.endsWith(".scala"),
      s"expected a source file, got '${top.definitionFile}'"
    )
  }

  test("ranking is stable across runs on the same payload") {
    val again =
      OpaqueCandidateExplorer.explore(FixtureIndex.index, FixtureIndex.facts)
    assertEquals(
      again.map(candidate => (candidate.name, candidate.size)),
      candidates.map(candidate => (candidate.name, candidate.size))
    )
  }

  test("no candidate reuses a type name already in the sources") {
    val existing = OpaqueCandidateExplorer.existingTypeNames(FixtureIndex.index)
    val clash = candidates.map(_.name).filter(existing.contains)
    assert(
      clash.isEmpty,
      s"candidates collide with existing types: ${clash.mkString(", ")}"
    )
    // And the guard is actually exercised: `BranchName` exists, so the widest
    // String flow had to be renamed rather than trivially not colliding.
    assert(existing.contains("BranchName"))
  }

  test("clusters are not reported twice under different seeds") {
    val memberSets = candidates.map(_.members.toSet)
    assertEquals(memberSets.distinct.size, memberSets.size)
  }

  test("emitted HOCON is parsed by PropagateOpaqueTypeConfig.decoder") {
    val hocon = OpaqueCandidateExplorer.renderHocon(candidates)
    val conf = Conf
      .parseString(hocon)
      .get

    val parsed = conf
      .getOrElse("PropagateOpaqueType")(PropagateOpaqueTypeConfig.default)
      .get

    assertEquals(parsed.types.size, candidates.size)
    assertEquals(parsed.types.map(_.name), candidates.map(_.name))
    assertEquals(parsed.types.map(_.seeds), candidates.map(_.seeds))
    assert(parsed.types.forall(_.widen.isEmpty))
  }

  test("the basic-type set is honoured") {
    val onlyInts = OpaqueCandidateExplorer.explore(
      FixtureIndex.index,
      FixtureIndex.facts,
      ExplorerConfig(basicTypes = List("scala/Int#"))
    )
    assert(onlyInts.forall(_.underlying == "scala/Int#"))
  }

  test("minClusterSize is a threshold, and raising it converges to empty") {
    val maxSize = candidates.map(_.size).max

    // At the threshold only the largest flows survive, and every one of them
    // clears it -- the knob keeps clusters, it does not cap their count.
    val atMax = OpaqueCandidateExplorer.explore(
      FixtureIndex.index,
      FixtureIndex.facts,
      ExplorerConfig(minClusterSize = maxSize)
    )
    assert(atMax.nonEmpty)
    assert(atMax.forall(_.size >= maxSize))
    assertEquals(atMax.map(_.size).distinct, List(maxSize))

    // One past the largest cluster: nothing is worth converting, so the rule
    // hands back nothing -- the convergence a fixed `topN` never reached.
    val past = OpaqueCandidateExplorer.explore(
      FixtureIndex.index,
      FixtureIndex.facts,
      ExplorerConfig(minClusterSize = maxSize + 1)
    )
    assert(past.isEmpty)
  }

  test("symbol segmentation survives parameters and type parameters") {
    assertEquals(
      OpaqueCandidateExplorer.segments("a/b/C#find().(id)"),
      List("a/", "b/", "C#", "find().", "(id)")
    )
    assertEquals(OpaqueCandidateExplorer.displayName("a/b/C#find().(id)"), "id")
    assertEquals(OpaqueCandidateExplorer.displayName("a/b/C#userId."), "userId")
    assertEquals(OpaqueCandidateExplorer.ownerOf("a/b/C#userId."), "a/b/C#")
  }
}
