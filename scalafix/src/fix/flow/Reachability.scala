package fix.flow

/** Where a value goes, and what it runs into on the way.
  *
  * The graph half of a change closure, with none of the policy. `Closure`
  * answers the opaque question -- which nodes convert, where to wrap, where to
  * unwrap -- because an opaque type can cross a boundary by being wrapped. A
  * container abstracted to `S[_]` cannot: there is no expression that turns an
  * `S[A]` back into the `Seq[A]` some other signature asked for. So the two
  * rules share the traversal and disagree about the verdict, and only the
  * traversal lives here.
  */
object Reachability {

  /** Every node the value at `seed` flows into, `seed` included.
    *
    * Forward only, and cycle-safe: a parameter can flow to a body, the body to
    * a return, and the return back to a caller that feeds the same parameter.
    */
  def forward(seed: Node, edges: List[Edge]): Set[Node] = {
    val outgoing = edges.groupBy(_.from)

    @annotation.tailrec
    def walk(frontier: List[Node], seen: Set[Node]): Set[Node] =
      frontier match {
        case Nil => seen
        case node :: rest =>
          val next = outgoing
            .getOrElse(node, Nil)
            .map(_.to)
            .filterNot(seen.contains)
          walk(next ++ rest, seen ++ next)
      }

    walk(List(seed), Set(seed))
  }

  /** The first node the value reaches that `stops` rejects, in a stable order.
    *
    * Returning the node rather than a boolean is what lets a rule say *why* it
    * declined -- which signature the value escaped into, and where.
    */
  def firstStop(
      seed: Node,
      edges: List[Edge],
      stops: Node => Boolean
  ): Option[Node] =
    forward(seed, edges).toList.sorted.find(node => node != seed && stops(node))
}
