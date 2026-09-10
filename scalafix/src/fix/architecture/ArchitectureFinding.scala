package fix.architecture

import scala.meta.Tree

/** Something the grammar recognised as a violation. Always reported, never
  * rewritten -- there is no safe automatic fix from arbitrary logic into arrow
  * form.
  */
final case class ArchitectureFinding(tree: Tree, message: String)
