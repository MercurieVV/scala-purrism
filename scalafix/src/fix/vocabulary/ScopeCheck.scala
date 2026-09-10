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

  def inScope(filePackage: String, scope: PatternList): Boolean =
    filePackage.nonEmpty && scope.matches(filePackage)
}
