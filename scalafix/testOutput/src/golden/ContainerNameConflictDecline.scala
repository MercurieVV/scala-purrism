
package golden

final class ContainerNameConflictDecline {
  // `S`, `C`, `G` -- every candidate name `HktRewriter.freshTypeParamName`
  // tries -- are all already type parameters here, so the rule has no free
  // name to introduce for the widened container.
  private def names[S, C, G]( 
      users: List[String],
      s: S,
      c: C,
      g: G
  ): List[String] =
    users.map(user => user.toUpperCase)
}
