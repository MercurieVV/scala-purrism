package fix.opaque

/** Drives `Closure.compute` through a fake symbol table, so the graph algorithm
  * is pinned without needing a compiler or any SemanticDB on disk.
  *
  * Shapes are modelled on the real cases in `gh-tasks-llm-executor`:
  * `acquireWorktree: Kleisli[F, (Path, Path, String, Option[String], L), Unit]`,
  * `ensureBranch` being fed both a branch name and `baseBranch`'s payload, and
  * `awaitPullRequestChecks` being fed only `pullRequest.number.toString`.
  */
class ClosureSuite extends munit.FunSuite {

  private val Str = "scala/Predef.String#"
  private val Int = "scala/Int#"

  final case class FakeFacts(
      edges: List[Edge],
      origins: Map[String, Origin],
      types: Map[Node, String]
  ) extends Facts {
    def origin(symbol: String): Origin =
      origins.getOrElse(symbol, Origin.Project)
    def typeAt(node: Node): Option[String] = types.get(node)
  }

  private def node(symbol: String, indices: Int*): Node =
    Node(symbol, TypePath(indices.toList))

  private def edge(from: Node, to: Node, kind: EdgeKind = EdgeKind.ArgToParam) =
    Edge(from, to, kind, Provenance(s"${from.symbol}.scala", 1, 1, 1, 2))

  /** Builder that types every mentioned node as String unless told otherwise.
    */
  private def facts(
      edges: List[Edge],
      origins: Map[String, Origin] = Map.empty,
      overrides: Map[Node, String] = Map.empty
  ): FakeFacts = {
    val mentioned = edges.flatMap(e => List(e.from, e.to)).distinct
    val types = mentioned.map(n => n -> overrides.getOrElse(n, Str)).toMap
    FakeFacts(edges, origins, types)
  }

  // The acquireWorktree shape: branchName at input tuple slot 2, baseBranch at
  // slot 3 with its payload one level deeper.
  private val acquire = "Git/acquireWorktree."
  private val branchSlot = node(acquire, 1, 2)
  private val baseSlot = node(acquire, 1, 3)
  private val basePayload = node(acquire, 1, 3, 0)

  test("1: tuple slot isolation - Option sibling and its payload stay out") {
    val seed = node("TaskRun#branchName.")
    // Both the Option slot and its String payload are real, typed nodes that
    // flow somewhere -- they are excluded because no edge connects them to the
    // seed, not because the graph forgot about them.
    val f = facts(
      edges = List(
        edge(seed, branchSlot),
        edge(node("TaskRun#baseBranch."), baseSlot),
        edge(basePayload, node("Git/ensureBranch().(name)"))
      ),
      overrides = Map(baseSlot -> "scala/Option#")
    )

    val result = Closure.compute(Set(seed), f, Str)

    assert(result.members.contains(branchSlot), "branch slot must be converted")
    assert(
      !result.members.contains(baseSlot),
      "Option[String] slot must not be"
    )
    assert(
      !result.members.contains(basePayload),
      "the Option's payload is a different node and must not be dragged in"
    )
  }

  test("2: local reshaping carries the slot across a tuple arity change") {
    val release = "Git/releaseWorktree."
    // 5-tuple slot 2 -> 4-tuple slot 2, via the lambda's pattern binder.
    val binder = node("local:branchName")
    val f = facts(
      List(
        edge(branchSlot, binder, EdgeKind.TupleSlot),
        edge(binder, node(release, 1, 2), EdgeKind.Reshape)
      )
    )

    val result = Closure.compute(Set(branchSlot), f, Str)

    assert(result.members.contains(node(release, 1, 2)))
  }

  test("3: an uncovered inbound value demotes the node and is reported") {
    val ensure = node("Git/ensureBranch().(branchName)")
    val f = facts(
      List(
        edge(node("TaskRun#branchName."), ensure),
        edge(basePayload, ensure, EdgeKind.HktPassthrough)
      )
    )

    val result = Closure.compute(Set(node("TaskRun#branchName.")), f, Str)

    assertEquals(result.mergePoints.map(_.node), List(ensure))
    assertEquals(result.mergePoints.map(_.intruder), List(basePayload))
    assert(!result.members.contains(ensure), "demoted node keeps its own type")
    // The seed-side call still has to hand over a raw value.
    assert(
      result.leaves.exists(_.counterpart == ensure),
      s"expected an unwrap boundary into $ensure, got ${result.leaves}"
    )
  }

  test("4: demotion is local - the seed and its other reaches survive") {
    val ensure = node("Git/ensureBranch().(branchName)")
    val publish = node("PublishRequest#branchName.")
    val seed = node("TaskRun#branchName.")
    val f = facts(
      List(
        edge(seed, ensure),
        edge(basePayload, ensure, EdgeKind.HktPassthrough),
        edge(seed, publish)
      )
    )

    val result = Closure.compute(Set(seed), f, Str)

    assert(result.members.contains(seed))
    assert(result.members.contains(publish))
    assert(!result.members.contains(ensure))
  }

  test("widen: naming the intruder converts it and clears the merge point") {
    val ensure = node("Git/ensureBranch().(branchName)")
    val seed = node("TaskRun#branchName.")
    val f = facts(
      List(
        edge(seed, ensure),
        edge(basePayload, ensure, EdgeKind.HktPassthrough)
      )
    )

    val result =
      Closure.compute(Set(seed), f, Str, widen = Set(ensure.symbol))

    assertEquals(result.mergePoints, Nil)
    assert(result.members.contains(ensure), "the shared parameter converts")
    assert(
      result.members.contains(basePayload),
      "and so does the Option payload that fed it, giving Option[BranchName]"
    )
  }

  test("container payloads convert, so Option[String] becomes Option[Opaque]") {
    // The payload sits one type-argument deeper than its container; nothing
    // about Option is special-cased.
    val seed = node("TaskRun#branchName.")
    val optionPayload = node("PublishRequest#tags.", 0)
    val base = facts(List(edge(seed, optionPayload)))
    val f = base.copy(types =
      base.types ++ Map(node("PublishRequest#tags.") -> "scala/Option#")
    )

    val result = Closure.compute(Set(seed), f, Str)

    assertEquals(result.members, List(seed, optionPayload).sorted)
    assert(
      !result.members.contains(node("PublishRequest#tags.")),
      "the container itself is not a String node and is not converted"
    )
  }

  test("5: a node nothing seeded ever reaches never enters the closure") {
    // awaitPullRequestChecks is only ever fed pullRequest.number.toString, so
    // symbol-level flow keeps it out without any conflict machinery.
    val await = node("github/awaitPullRequestChecks().(branchName)")
    val toStringExpr = node("expr:pullRequest.number.toString")
    val seed = node("TaskRun#branchName.")
    val f = facts(
      List(
        edge(toStringExpr, await),
        edge(seed, node("PublishRequest#branchName."))
      ),
      origins = Map(toStringExpr.symbol -> Origin.Expression)
    )

    val result = Closure.compute(Set(seed), f, Str)

    assert(
      !result.members.contains(await),
      "no seeded value reaches it, so it must stay as the underlying type"
    )
    assert(result.mergePoints.isEmpty, "and it is not a merge point either")
  }

  test("6: seeding any field alias yields the same closure") {
    // SemanticDB emits four symbols for one case-class field -- getter, ctor
    // param, apply param, copy param (verified against BusinessLogic.scala's
    // payload). They are the same storage, so the graph builder emits alias
    // edges in both directions; otherwise which symbol you happened to seed
    // would change the result.
    val getter = node("TaskRun#branchName.")
    val ctor = node("TaskRun#`<init>`().(branchName)")
    val apply = node("TaskRun.apply().(branchName)")
    val copy = node("TaskRun#copy().(branchName)")
    val downstream = node("Git/acquireWorktree().(branchName)")
    val aliases = List(ctor, apply, copy).flatMap { alias =>
      List(
        edge(getter, alias, EdgeKind.FieldAlias),
        edge(alias, getter, EdgeKind.FieldAlias)
      )
    }
    val f = facts(aliases :+ edge(getter, downstream))

    val fromGetter = Closure.compute(Set(getter), f, Str).members
    val fromCtor = Closure.compute(Set(ctor), f, Str).members

    assertEquals(fromCtor, fromGetter)
    assert(fromGetter.contains(downstream))
  }

  test("7: already-converted seed produces an empty closure") {
    val seed = node("TaskRun#branchName.")
    val base =
      facts(List(edge(seed, node("Git/acquireWorktree().(branchName)"))))
    // Simulate a re-run against fresh SemanticDB: the seed is BranchName now.
    val converted =
      base.copy(types = base.types.updated(seed, "app/BranchName#"))

    val result = Closure.compute(Set(seed), converted, Str)

    assertEquals(result.members, Nil)
    assertEquals(result.genesis, Nil)
    assertEquals(result.leaves, Nil)
  }

  test("8: result is independent of fact insertion order") {
    val seed = node("TaskRun#branchName.")
    val ensure = node("Git/ensureBranch().(branchName)")
    val edges = List(
      edge(seed, node("PublishRequest#branchName.")),
      edge(seed, branchSlot),
      edge(branchSlot, node("Git/releaseWorktree.", 1, 2)),
      edge(seed, ensure),
      edge(basePayload, ensure, EdgeKind.HktPassthrough),
      edge(node("expr:literal"), seed)
    )
    val origins = Map("expr:literal" -> Origin.Expression)

    val reference = Closure.compute(Set(seed), facts(edges, origins), Str)
    val rng = new scala.util.Random(0)

    (1 to 25).foreach { i =>
      val shuffled = Closure.compute(
        Set(seed),
        facts(rng.shuffle(edges), origins),
        Str
      )
      assertEquals(
        shuffled.members,
        reference.members,
        s"members differ (run $i)"
      )
      assertEquals(
        shuffled.genesis,
        reference.genesis,
        s"genesis differs (run $i)"
      )
      assertEquals(shuffled.leaves, reference.leaves, s"leaves differ (run $i)")
      assertEquals(
        shuffled.mergePoints,
        reference.mergePoints,
        s"merge points differ (run $i)"
      )
    }
  }

  test("genesis: an inbound expression is a wrap site, not a conflict") {
    val seed = node("TaskRun#branchName.")
    val literal = node("expr:s\"task-$taskId\"")
    val f = facts(
      List(edge(literal, seed)),
      origins = Map(literal.symbol -> Origin.Expression)
    )

    val result = Closure.compute(Set(seed), f, Str)

    assertEquals(result.mergePoints, Nil)
    assertEquals(result.genesis.map(_.node), List(seed))
    assertEquals(result.genesis.map(_.counterpart), List(literal))
  }

  test("leaf: flow into a foreign symbol is an unwrap site") {
    val seed = node("TaskRun#branchName.")
    val procArg = node("os/proc().(args)")
    val f = facts(
      List(edge(seed, procArg)),
      origins = Map(procArg.symbol -> Origin.Foreign)
    )

    val result = Closure.compute(Set(seed), f, Str)

    assertEquals(result.mergePoints, Nil)
    assertEquals(result.leaves.map(_.counterpart), List(procArg))
  }

  test("a varargs command list is a boundary, not a member") {
    // call(root, "git", "branch", branchName): the varargs parameter is a
    // project symbol of the right element type, so nothing but its own
    // ineligibility keeps it out. If it joined the closure it would become
    // BranchName* and the literals would be "wrapped" as BranchName("git").
    val seed = node("TaskRun#branchName.")
    val command = node("Git/call().(command)")
    val base = facts(
      List(
        edge(seed, command),
        edge(node("expr:\"git\""), command),
        edge(node("expr:\"branch\""), command)
      ),
      origins = Map(
        "expr:\"git\"" -> Origin.Expression,
        "expr:\"branch\"" -> Origin.Expression
      )
    )
    // A String* resolves to the repeated type at its root, not to String.
    val f = base.copy(types = base.types.updated(command, "scala/Array#"))

    val result = Closure.compute(Set(seed), f, Str)

    assert(!result.members.contains(command), "varargs must not be retyped")
    assertEquals(result.leaves.map(_.counterpart), List(command))
    assert(
      result.genesis.isEmpty,
      s"literals must not be wrapped; got ${result.genesis.map(_.counterpart.render)}"
    )
  }

  test("ineligible nodes are never entered even when an edge points at them") {
    val seed = node("TaskRun#branchName.")
    val intField = node("TaskRun#retries.")
    val base = facts(List(edge(seed, intField)))
    val f = base.copy(types = base.types.updated(intField, Int))

    val result = Closure.compute(Set(seed), f, Str)

    assert(!result.members.contains(intField))
  }
}
