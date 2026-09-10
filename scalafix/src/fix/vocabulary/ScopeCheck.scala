package fix.vocabulary

import scala.meta._

object ScopeCheck {
  def filePackage(tree: Tree): String = {
    def render(ref: Term): String = ref match {
      case Term.Name(name)         => name
      case Term.Select(qual, name) => s"${render(qual)}.${name.value}"
      case other                   => other.syntax
    }
    tree.collect { case p: Pkg => render(p.ref) }.headOption.getOrElse("")
  }

  /** An empty `scope` list means "the whole module" -- every file in it is in
    * scope, not none. A non-empty `scope` narrows that to files whose own
    * package matches one of the patterns.
    */
  def inScope(filePackage: String, scope: PatternList): Boolean =
    scope.isEmpty || (filePackage.nonEmpty && scope.matches(filePackage))
}
