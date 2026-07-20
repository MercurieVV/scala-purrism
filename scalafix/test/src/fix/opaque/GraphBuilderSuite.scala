package fix.opaque

/** Builds the real flow graph from the `testInput` fixture's SemanticDB payload
  * and runs the closure over it, so the loader, the builder and the algorithm
  * are exercised together against compiler output.
  */
class GraphBuilderSuite extends munit.FunSuite {

  // typeAt canonicalises aliases, so compare against the canonical form.
  private val Str = SemanticdbIndex.canonicalType("scala/Predef.String#")

  private lazy val index = FixtureIndex.index
  private lazy val graph = FixtureIndex.graph
  private lazy val edges = graph.edges

  private def userIdGetter: String =
    index.symbolInfo.keys
      .find(sym => sym.endsWith("#userId.") && sym.contains("User"))
      .getOrElse(fail("no User#userId getter"))

  private def facts: Facts = FixtureIndex.facts

  test("field aliases are linked in both directions") {
    val aliasEdges = edges.filter(_.kind == EdgeKind.FieldAlias)
    val touching = aliasEdges.filter(e =>
      e.from.symbol.contains("userId") || e.to.symbol.contains("userId")
    )

    assert(touching.nonEmpty, "expected alias edges for userId")
    // Every alias edge must have a mirror.
    touching.foreach { e =>
      assert(
        touching.exists(m => m.from == e.to && m.to == e.from),
        s"missing mirror for ${e.from.render} -> ${e.to.render}"
      )
    }
  }

  test("call arguments are linked to the callee's parameters") {
    // processUser(userId) calls repo.findById(userId): the argument symbol must
    // flow into findById's parameter symbol.
    val findByIdParam = index.symbolInfo.keys
      .find(sym => sym.contains("findById") && sym.contains("(userId)"))
      .getOrElse(
        fail(
          s"no findById parameter symbol in ${index.symbolInfo.keys.toList.sorted}"
        )
      )

    assert(
      edges.exists(e =>
        e.kind == EdgeKind.ArgToParam && e.to.symbol == findByIdParam
      ),
      s"expected an ArgToParam edge into $findByIdParam; got " +
        edges
          .filter(_.kind == EdgeKind.ArgToParam)
          .map(e => s"${e.from.render}->${e.to.render}")
    )
  }

  test("a field seed reaches its aliases and stops where nothing flows") {
    // Nothing in OpaqueSmoke.scala ever passes a User#userId to processUser --
    // the two are independent Strings that merely share a name. Forward-only
    // propagation must therefore stop at the field's own aliases. (Undirected
    // reachability would have walked backwards into processUser and converted
    // an unrelated parameter.)
    val seed = Node(userIdGetter, TypePath.root)
    val result = Closure.compute(Set(seed), facts, Str)

    assertEquals(
      result.members.map(_.symbol).sorted,
      List(
        "_empty_/User#`<init>`().(userId)",
        "_empty_/User#copy().(userId)",
        "_empty_/User#userId.",
        "_empty_/User.apply().(userId)"
      )
    )
  }

  test("a parameter seed flows on to the callee it is passed to") {
    // processUser(userId) does call repo.findById(userId), so seeding
    // processUser's own parameter must reach findById's.
    val processUserParam = symbolEndingWith("processUser().(userId)")
    val result =
      Closure.compute(Set(Node(processUserParam, TypePath.root)), facts, Str)

    assert(
      result.members.exists(_.symbol.contains("findById")),
      s"findById's parameter should be reached; got ${result.members.map(_.render)}"
    )
  }

  test("nothing outside the String flow is converted") {
    val seed = Node(userIdGetter, TypePath.root)
    val result = Closure.compute(Set(seed), facts, Str)

    assert(
      !result.members.exists(_.symbol.contains("#name.")),
      s"the unrelated `name: String` field must stay put; got ${result.members.map(_.render)}"
    )
  }

  private def symbolEndingWith(suffix: String): String =
    index.symbolInfo.keys
      .find(_.endsWith(suffix))
      .getOrElse(fail(s"no symbol ending in $suffix"))

  test("a Kleisli's input tuple slots are addressable by path") {
    val acquire = symbolEndingWith("Worktrees#acquire().")
    // (String, String, String, Option[String]) -- branchName at slot 2.
    assertEquals(index.functionInputArgIndex(acquire), Some(1))
    assertEquals(index.typeAt(Node(acquire, TypePath(List(1, 2)))), Some(Str))
    assertEquals(
      index.typeAt(Node(acquire, TypePath(List(1, 3)))),
      Some("scala/Option#"),
      "the Option sibling must stay a distinct node from a bare String"
    )
  }

  test("loose auto-tupled arguments reach the right Kleisli slot") {
    val acquire = symbolEndingWith("Worktrees#acquire().")
    val branchField = symbolEndingWith("Run#branchName.")

    // acquire(run.root, run.worktree, run.branchName, base): the third loose
    // argument must land in slot 2, not be treated as one whole input.
    assert(
      edges.exists(e =>
        e.from.symbol == branchField &&
          e.to == Node(acquire, TypePath(List(1, 2)))
      ),
      s"expected branchName -> acquire[1,2]; got " +
        edges
          .filter(_.to.symbol == acquire)
          .map(e => s"${e.from.render}->${e.to.render}")
    )
  }

  test("an explicit tuple argument reaches the right Kleisli slot") {
    val exists = symbolEndingWith("Worktrees#branchExists().")
    val branchField = symbolEndingWith("Run#branchName.")

    assert(
      edges.exists(e =>
        e.from.symbol == branchField &&
          e.to == Node(exists, TypePath(List(1, 1)))
      ),
      s"expected branchName -> branchExists[1,1]; got " +
        edges
          .filter(_.to.symbol == exists)
          .map(e => s"${e.from.render}->${e.to.render}")
    )
  }

  test("closure flows from a field through Kleisli slots") {
    val branchField = symbolEndingWith("Run#branchName.")
    val result =
      Closure.compute(Set(Node(branchField, TypePath.root)), facts, Str)

    val acquire = symbolEndingWith("Worktrees#acquire().")
    val exists = symbolEndingWith("Worktrees#branchExists().")
    val release = symbolEndingWith("Worktrees#release().")

    assert(result.members.contains(Node(acquire, TypePath(List(1, 2)))))
    assert(result.members.contains(Node(exists, TypePath(List(1, 1)))))
    // `release` is fed by the Reshaper as well as directly, and the reshaped
    // side is not covered, so it is demoted to a merge point rather than
    // converted. Widening it is covered by its own test below.
    assert(
      !result.members.contains(Node(release, TypePath(List(1, 2)))),
      "release is a merge point until the reshape chain is widened"
    )
    assert(
      result.mergePoints.exists(_.node == Node(release, TypePath(List(1, 2)))),
      s"expected release[1,2] to be reported; got ${result.mergePoints.map(_.node.render)}"
    )
    assert(
      !result.members.exists(_.symbol.endsWith("Run#root.")),
      s"the sibling root field must not be dragged in; got ${result.members.map(_.render)}"
    )
  }

  test("a local[...] reshaping types its input slots and links the binders") {
    // The explicit type argument has no symbol of its own, so it gets a
    // synthetic node typed by resolving the written type.
    val inputSlots = graph.syntheticTypes.filter { case (node, _) =>
      node.symbol.startsWith("localinput:")
    }
    assert(inputSlots.nonEmpty, "expected synthetic nodes for local[...] input")
    assertEquals(
      inputSlots.get(
        inputSlots.keys
          .find(_.path == TypePath(List(2)))
          .getOrElse(fail("no slot 2"))
      ),
      Some(Str),
      "slot 2 of the reshaped input is the branch name"
    )
    assert(
      inputSlots.keys.exists(node =>
        node.path == TypePath(List(3)) &&
          inputSlots.get(node).contains("scala/Option#")
      ),
      s"slot 3 must stay Option, got $inputSlots"
    )
  }

  test("a local[...] reshaping links its output tuple to the wrapped arrow") {
    val release = symbolEndingWith("Worktrees#release().")
    assert(
      edges.exists(e =>
        e.kind == EdgeKind.Reshape &&
          e.to == Node(release, TypePath(List(1, 2)))
      ),
      s"expected the reshaped output slot 2 to feed release[1,2]; got " +
        edges
          .filter(_.kind == EdgeKind.Reshape)
          .map(e => s"${e.from.render}->${e.to.render}")
    )
  }

  test("reshaping carries a seed through to the wrapped arrow when widened") {
    val branchField = symbolEndingWith("Run#branchName.")
    val release = symbolEndingWith("Worktrees#release().")
    val reshapeBinder = edges
      .collectFirst {
        case e if e.kind == EdgeKind.Reshape && e.to.symbol.contains("local") =>
          e.to.symbol
      }
    val inputOwner =
      graph.syntheticTypes.keys
        .find(_.symbol.startsWith("localinput:"))
        .map(_.symbol)

    val widen =
      (reshapeBinder.toList ++ inputOwner.toList ++ List(release)).toSet
    val result =
      Closure.compute(Set(Node(branchField, TypePath.root)), facts, Str, widen)

    assert(
      result.members.contains(Node(release, TypePath(List(1, 2)))),
      s"widening should carry the value across the reshape; got ${result.members.map(_.render)}"
    )
  }

  test("a container payload flows to a named binder") {
    val qualifyParam = symbolEndingWith("qualify().(branchName)")
    assert(
      edges.exists(e =>
        e.kind == EdgeKind.HktPassthrough && e.to.symbol == qualifyParam
      ),
      s"expected Option payload -> qualify's parameter via map; got " +
        edges
          .filter(_.kind == EdgeKind.HktPassthrough)
          .map(e => s"${e.from.render}->${e.to.render}")
    )
  }

  test("a container payload flows through a placeholder argument") {
    val qualifyParam = symbolEndingWith("qualify().(branchName)")
    val placeholderEdges = edges.filter(e =>
      e.kind == EdgeKind.HktPassthrough &&
        e.to.symbol == qualifyParam &&
        e.from.path == TypePath(List(0))
    )
    assert(
      placeholderEdges.nonEmpty,
      s"expected base.map(qualify(_, \"origin\")) to link the Option payload " +
        s"to qualify's first parameter; got ${edges.filter(_.kind == EdgeKind.HktPassthrough).map(e => s"${e.from.render}->${e.to.render}")}"
    )
  }

  test("the graph is deterministic across rebuilds") {
    val again = new GraphBuilder(index, FixtureIndex.sourceroot).build()
    assertEquals(
      again.edges.map(e => (e.from, e.to, e.kind)),
      edges.map(e => (e.from, e.to, e.kind))
    )
    assertEquals(again.syntheticTypes, graph.syntheticTypes)
  }
}
