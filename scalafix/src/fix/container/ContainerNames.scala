package fix

/** How a rule's `containers` list is matched against a constructor's name.
  *
  * The list is normally spelled out -- `["List", "Seq", "Vector"]` -- because a
  * widening rule with a fixed subject is a rule whose blast radius the user can
  * predict. `["*"]` says the opposite: take every candidate. What counts as a
  * candidate is then the caller's question, and the two callers answer it
  * differently by necessity. A rule holds the Cats index and requires that the
  * constructor is one the index has a theory of; `WidenScope` reads SemanticDB
  * signatures and is handed that same test as a predicate, because a scope that
  * predicts more widenings than the rules perform rewrites call sites of
  * definitions nothing changed.
  *
  * The wildcard is a distinct value rather than the empty list because the
  * empty list already means something in every one of these rules -- widen
  * nothing, or cede nothing -- and silently inverting it would change what
  * existing configurations do.
  */
object ContainerNames {
  val Wildcard: String = "*"

  def isWildcard(containers: List[String]): Boolean =
    containers.contains(Wildcard)

  /** Whether the list names this constructor, the wildcard counting as any
    * *writable* name.
    *
    * Writable is the whole of the wildcard's condition. A spelled-out list is
    * writable by construction -- someone typed those names -- but the wildcard
    * accepts whatever the last segment of a symbol happens to be, and that is
    * not always a type: `IndexedSeq.empty` arrives here as `Delegate#empty()`,
    * and a type lambda's parameter as `[F]`. Both were appended to a call
    * site's type arguments before this guard, producing
    * `recording[F, Delegate#empty()](...)`.
    */
  def matches(containers: List[String], simpleName: String): Boolean =
    if (isWildcard(containers)) isIdentifier(simpleName)
    else containers.contains(simpleName)

  /** Whether a name is one a type argument can be written with. */
  def isIdentifier(name: String): Boolean =
    name.nonEmpty &&
      (name.head.isLetter || name.head == '_') &&
      name.forall(character => character.isLetterOrDigit || character == '_')
}
