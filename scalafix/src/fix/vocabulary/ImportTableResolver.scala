package fix.vocabulary

import scala.meta._

/** Resolves named types to fully-qualified names using only what this file's
  * own source spells out -- no compiler, no SemanticDB. Used by
  * `RestrictVocabulary` when it runs on code that hasn't compiled yet.
  *
  * Only resolves what is syntactically unambiguous: a fully-qualified reference
  * written inline, and a plain, multi-, or renamed import. A wildcard import or
  * a same-package reference with no import at all returns `None` -- "unknown",
  * not a guess -- because guessing here would either wrongly clear a banned
  * type or wrongly flag an allowed one.
  */
final class ImportTableResolver private (byName: Map[String, String])
    extends TypeResolver {

  /** The type's fully-qualified name (dotted, e.g. "cats.data.Kleisli"), if
    * this file's own source resolves it unambiguously; `None` otherwise.
    */
  def resolve(tpe: Type): Option[String] = tpe match {
    case Type.Name(value) => byName.get(value)
    case sel: Type.Select => Some(ImportTableResolver.dottedTypeSelect(sel))
    case _                => None
  }

  /** A bare name is exempt when it's one of its own enclosing definitions' type
    * parameters; a qualified reference never is.
    */
  def isExempt(tpe: Type): Boolean = tpe match {
    case name: Type.Name => ImportTableResolver.isEnclosingTypeParameter(name)
    case _               => false
  }
}

object ImportTableResolver {

  def fromSource(source: Source): ImportTableResolver =
    new ImportTableResolver(importTable(source))

  /** True if `name` names a type parameter declared by one of its own enclosing
    * definitions -- a purely syntactic check (walks the tree's own parent
    * chain), so it applies whether or not a compiled payload exists.
    *
    * Known blind spot: an outer type parameter shadowed by an unrelated
    * concrete type of the same name in a nested scope reads as exempt here,
    * same as every other approximation this resolver makes.
    */
  def isEnclosingTypeParameter(name: Type.Name): Boolean =
    enclosing(name).flatMap(typeParametersOf).exists(_.name.value == name.value)

  private def typeParametersOf(tree: Tree): List[Type.Param] = tree match {
    case defn: Defn.Def => defn.paramClauseGroups.flatMap(_.tparamClause.values)
    case cls: Defn.Class => cls.tparamClause.values
    case tr: Defn.Trait  => tr.tparamClause.values
    case en: Defn.Enum   => en.tparamClause.values
    case _               => Nil
  }

  private def enclosing(tree: Tree): List[Tree] = tree.parent match {
    case Some(parent) => parent :: enclosing(parent)
    case None         => Nil
  }

  private def importTable(source: Source): Map[String, String] =
    source.collect { case i: Import => i }.flatMap(importEntries).toMap

  private def importEntries(imp: Import): List[(String, String)] =
    imp.importers.flatMap { case Importer(ref, importees) =>
      val base = dottedTermPath(ref)
      importees.flatMap {
        case Importee.Name(Name(value)) =>
          List(value -> s"$base.$value")
        case Importee.Rename(Name(from), Name(to)) =>
          List(to -> s"$base.$from")
        case _ =>
          // Wildcard, unimport, given import -- not resolvable (wildcard) or
          // not a type-name binding (unimport/given) this resolver handles.
          Nil
      }
    }

  private def dottedTypeSelect(sel: Type.Select): String =
    s"${dottedTermPath(sel.qual)}.${sel.name.value}"

  private def dottedTermPath(ref: Term): String = ref match {
    case Term.Name(value)     => value
    case Term.Select(qual, n) => s"${dottedTermPath(qual)}.${n.value}"
    case other                => other.syntax
  }
}
